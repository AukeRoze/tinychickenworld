package com.youtubeauto.script.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.script.anthropic.GeneratedScript;
import com.youtubeauto.script.api.dto.*;
import com.youtubeauto.script.bible.BibleLoader;
import com.youtubeauto.script.config.DedupeProperties;
import com.youtubeauto.script.dedupe.*;
import com.youtubeauto.script.domain.*;
import com.youtubeauto.script.repository.*;
import com.youtubeauto.script.service.ScriptGenerator.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pipeline: pick VariationProfile -> generate -> fingerprint -> dedupe check.
 * On duplicate, regenerate with a fresh profile up to dedupe.maxRetries.
 *
 * Now scenes carry per-character dialogue lines, characters list, and
 * locationId — everything downstream needs to render the same cast.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptOrchestrator {

    private final ScriptJobRepository jobRepo;
    private final ScriptRepository scriptRepo;
    private final ScriptGenerator generator;
    private final VariationSelector variationSelector;
    private final DuplicateDetectionService dedupe;
    private final DedupeProperties dedupeProps;
    private final BibleLoader bibleLoader;
    private final StructureValidator structureValidator;
    private final ComedyValidator comedyValidator;
    private final PacingValidator pacingValidator;
    private final TransformationValidator transformationValidator;
    private final AccessoryValidator accessoryValidator;
    private final ScriptCritic critic;
    private final com.youtubeauto.script.config.CriticProperties criticProps;
    private final DialoguePolisher dialoguePolisher;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Self-ref for @Async dispatch through the proxy (direct this.runAsync()
     *  would run synchronously inside submit's transaction and rollback-only
     *  state would tear it down). */
    @Autowired @Lazy private ScriptOrchestrator self;

    public ScriptJobResponse submit(GenerateScriptRequest req) {
        ScriptJob job = saveNewJob(req);
        self.runAsync(job.getId(), req);
        return new ScriptJobResponse(job.getId(), job.getStatus());
    }

    /**
     * Import a ready-made script as a COMPLETED job — the AI-free counterpart to
     * {@link #submit}. No Anthropic call, no dedupe/critic gates: the supplied
     * script is persisted verbatim so the orchestrator can poll it like any other
     * completed script job. Used when the Anthropic kill-switch is on and a
     * hand-authored script is fed in instead.
     */
    @Transactional
    public ScriptJobResponse importScript(ImportScriptRequest req) {
        GeneratedScript script = req.script();
        if (script == null || script.scenes() == null || script.scenes().isEmpty()) {
            throw new IllegalArgumentException("import: script has no scenes");
        }
        // Scene seq must be 1..N in order — the orchestrator and assembly rely on it.
        for (int i = 0; i < script.scenes().size(); i++) {
            if (script.scenes().get(i).seq() != i + 1) {
                throw new IllegalArgumentException(
                        "import: scene seq out of order at index " + i
                        + " (expected " + (i + 1) + ", got " + script.scenes().get(i).seq() + ")");
            }
        }

        ScriptJob job = ScriptJob.builder()
                .topic(req.topic())
                .audience(req.audience() != null && !req.audience().isBlank()
                        ? req.audience() : "kids_3_6")
                .targetSeconds(req.targetSeconds() != null ? req.targetSeconds() : 180)
                .status(JobStatus.COMPLETED)
                .build();
        job = jobRepo.save(job);

        String rawJson;
        try {
            rawJson = mapper.writeValueAsString(script);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("import: cannot serialise script", e);
        }
        ScriptFingerprint.Fingerprint fp = ScriptFingerprint.of(script);
        // Reuse the canonical persist path: builds the Script + ScriptScene rows
        // exactly like a generated job. Model tag "imported" marks the provenance;
        // token counts are null (no paid call), structure score a clean 100.
        VariationProfile profile = variationSelector.next();
        Result r = new Result(script, rawJson, "imported", null, null, profile, null);
        persist(job.getId(), r, fp, 0, 100, null, null, null, null);
        log.info("Job {} IMPORTED ({} scenes, no Anthropic call)",
                job.getId(), script.scenes().size());
        return new ScriptJobResponse(job.getId(), job.getStatus());
    }

    @Transactional
    public ScriptJob saveNewJob(GenerateScriptRequest req) {
        ScriptJob job = ScriptJob.builder()
                .topic(req.topic())
                .audience(req.audience())
                .targetSeconds(req.targetSeconds())
                .status(JobStatus.PENDING)
                .build();
        return jobRepo.save(job);
    }

    @Async("taskExecutor")
    public void runAsync(UUID jobId, GenerateScriptRequest req) {
        markStatus(jobId, JobStatus.GENERATING, null);
        VariationProfile profile = variationSelector.next();
        int duplicateHits = 0;
        int criticRewrites = 0;
        int comedyRewrites = 0;
        int pacingRewrites = 0;
        int transformRewrites = 0;
        int accessoryRewrites = 0;
        String structureFeedback = null;
        String criticFeedback = null;
        var episodeStructure = bibleLoader.getBible().episodeStructure();

        try {
            for (int attempt = 0; attempt <= dedupeProps.maxRetries(); attempt++) {
                Result r = generator.generate(req, profile, structureFeedback, criticFeedback);

                // Beat-sheet / structure gate: reject + re-prompt with the exact
                // violations (phase counts, durations, closing phase, location
                // variety) instead of trusting the LLM. On the final attempt we
                // proceed anyway so the pipeline isn't hard-blocked — the human
                // review gate + AI critic remain the backstop.
                List<String> violations = structureValidator.validate(
                        r.script(), episodeStructure, req.targetSeconds());
                if (!violations.isEmpty()) {
                    structureFeedback = String.join("; ", violations);
                    if (attempt < dedupeProps.maxRetries()) {
                        log.warn("Job {} structure invalid (attempt {}), re-prompting: {}",
                                jobId, attempt + 1, structureFeedback);
                        continue;
                    }
                    log.warn("Job {} structure still invalid after {} attempts, proceeding: {}",
                            jobId, attempt + 1, structureFeedback);
                } else {
                    structureFeedback = null;
                }

                // Deterministic structure score (0-100) from the violation count
                // — surfaced downstream so weak timing/arc is visible in Polish.
                int structureScore = structureScore(violations);

                // Deterministic PACING gate — words/sec, speaker changes and
                // the mandatory silent visual beat. One targeted re-prompt
                // with exact per-scene fixes; never blocks the final attempt.
                PacingValidator.Result pacing = pacingValidator.validate(r.script());
                if (pacing.failed() && pacingRewrites < 1 && attempt < dedupeProps.maxRetries()) {
                    pacingRewrites++;
                    criticFeedback = "MANDATORY PACING FIXES: " + String.join("; ", pacing.violations());
                    log.warn("Job {} pacing gate fail (rewrite {}/1): {}",
                            jobId, pacingRewrites, pacing.violations());
                    continue;
                }
                if (pacing.failed()) {
                    log.warn("Job {} pacing gate still failing, proceeding (backstop = human gate): {}",
                            jobId, pacing.violations());
                }

                // Deterministic COMEDY gate — checks the mechanical brand
                // contract (≥2 sound-effect beats, Mo's one comparison, a
                // distinctive plant-and-payoff callback) in pure Java. One
                // targeted re-prompt with exact fixes; soft heuristics (is
                // Bo's wordplay there?) only log. Never blocks the final
                // attempt — the LLM critic + human gate stay the backstop.
                ComedyValidator.Result comedy = comedyValidator.validate(r.script());
                comedy.warnings().forEach(wn -> log.info("Job {} comedy note: {}", jobId, wn));
                if (comedy.failed() && comedyRewrites < 1 && attempt < dedupeProps.maxRetries()) {
                    comedyRewrites++;
                    criticFeedback = "MANDATORY COMEDY FIXES: " + String.join("; ", comedy.violations());
                    log.warn("Job {} comedy gate fail (rewrite {}/1): {}",
                            jobId, comedyRewrites, comedy.violations());
                    continue;
                }
                if (comedy.failed()) {
                    log.warn("Job {} comedy gate still failing, proceeding (backstop = critic + human gate): {}",
                            jobId, comedy.violations());
                }

                // Deterministic LIVE-TRANSFORMATION gate — a scene that both
                // introduces a new character AND describes it hatching/being
                // revealed asks the video model for an egg→duckling morph it
                // can't do in one shot. One targeted re-prompt to split it into
                // a crack beat + a clean reveal beat; never blocks the final
                // attempt (human gate stays the backstop).
                TransformationValidator.Result trans = transformationValidator.validate(r.script());
                if (trans.failed() && transformRewrites < 1 && attempt < dedupeProps.maxRetries()) {
                    transformRewrites++;
                    criticFeedback = "MANDATORY TRANSFORMATION FIXES: " + String.join("; ", trans.violations());
                    log.warn("Job {} transformation gate fail (rewrite {}/1): {}",
                            jobId, transformRewrites, trans.violations());
                    continue;
                }
                if (trans.failed()) {
                    log.warn("Job {} transformation gate still failing, proceeding (backstop = human gate): {}",
                            jobId, trans.violations());
                }

                // Deterministic ACCESSORY-OWNERSHIP gate — a character may only
                // touch/wear ITS OWN accessory; an accessory it forbids ("Mo's
                // glasses", "Bo's hat") contradicts the DNA never-wear lock and
                // morphs the character. Primary, source-level prevention; the
                // orchestrator's compile-time guard stays the backstop. One
                // targeted re-prompt, never blocks the final attempt.
                AccessoryValidator.Result acc = accessoryValidator.validate(r.script());
                if (acc.failed() && accessoryRewrites < 1 && attempt < dedupeProps.maxRetries()) {
                    accessoryRewrites++;
                    criticFeedback = "MANDATORY ACCESSORY FIXES: " + String.join("; ", acc.violations());
                    log.warn("Job {} accessory gate fail (rewrite {}/1): {}",
                            jobId, accessoryRewrites, acc.violations());
                    continue;
                }
                if (acc.failed()) {
                    log.warn("Job {} accessory gate still failing, proceeding (backstop = compile-time guard + human gate): {}",
                            jobId, acc.violations());
                }

                // Cheap qualitative critic — scores arc / re-hook / ending /
                // age-language. One targeted rewrite if it's too weak, then we
                // proceed (the human gate + post-render AI critic are the
                // backstop). Fails safe to a passing score.
                Integer criticScore = null;
                Integer criticComedy = null, criticEmotional = null, criticPsychology = null;
                if (criticProps.isEnabled()) {
                    ScriptCritic.Critique cr = critic.review(r.script());
                    criticScore = cr.overall();
                    criticComedy = cr.comedy();
                    criticEmotional = cr.emotionalImpact();
                    criticPsychology = cr.childPsychology();
                    if (cr.overall() < criticProps.minScoreOr(70)
                            && criticRewrites < criticProps.maxRewritesOr(1)
                            && attempt < dedupeProps.maxRetries()) {
                        criticFeedback = cr.asFeedback();
                        criticRewrites++;
                        log.warn("Job {} story-critic {}/100 below {} (rewrite {}/{}): {}",
                                jobId, cr.overall(), criticProps.minScoreOr(70),
                                criticRewrites, criticProps.maxRewritesOr(1), criticFeedback);
                        continue;
                    }
                    criticFeedback = null;
                }

                // Optional dialogue punch-up on a stronger model — runs ONCE on
                // the winning attempt (structure + critic gates already passed),
                // just before commit, so the fingerprint and persisted scenes
                // reflect the final, sharpened spoken lines. No-op when disabled
                // or if the pass can't safely merge (it fails back to original).
                var polished = dialoguePolisher.polish(r.script());
                if (polished != r.script()) {
                    String rawJson = r.rawJson();
                    try { rawJson = mapper.writeValueAsString(polished); }
                    catch (Exception ignore) { /* keep original rawJson */ }
                    r = new Result(polished, rawJson, r.model(),
                            r.promptTokens(), r.completionTokens(), r.profile(), r.storyArc());
                }

                ScriptFingerprint.Fingerprint fp = ScriptFingerprint.of(r.script());

                try {
                    dedupe.assertNotDuplicate(fp);
                    persist(jobId, r, fp, attempt, structureScore, criticScore,
                            criticComedy, criticEmotional, criticPsychology);
                    if (duplicateHits > 0) recordDuplicateRejections(jobId, duplicateHits);
                    markStatus(jobId, JobStatus.COMPLETED, null);
                    log.info("Job {} COMPLETED (attempt={}, profile={})",
                            jobId, attempt + 1, profile.tag());
                    return;
                } catch (DuplicateDetectionException dup) {
                    duplicateHits++;
                    log.warn("Job {} duplicate hit (attempt {}, hamming={}): regenerating with new profile",
                            jobId, attempt + 1, dup.hammingDistance());
                    if (attempt == dedupeProps.maxRetries()) {
                        recordDuplicateRejections(jobId, duplicateHits);
                        markStatus(jobId, JobStatus.FAILED, "DUPLICATE: " + dup.getMessage());
                        return;
                    }
                    profile = variationSelector.nextAvoiding(profile);
                }
            }
        } catch (Exception e) {
            log.error("Job {} failed", jobId, e);
            markStatus(jobId, JobStatus.FAILED, e.getMessage());
        }
    }

    /** Maps structure violations to a 0-100 score (100 = clean). Each violation
     *  costs 15 points; the floor is 0. Deterministic, cheap, no LLM. */
    private int structureScore(List<String> violations) {
        if (violations == null || violations.isEmpty()) return 100;
        return Math.max(0, 100 - 15 * violations.size());
    }

    @Transactional
    protected void persist(UUID jobId, Result r, ScriptFingerprint.Fingerprint fp, int regenAttempts,
                           int structureScore, Integer criticScore,
                           Integer criticComedy, Integer criticEmotional, Integer criticPsychology) {
        int words = r.script().scenes().stream()
                .mapToInt(s -> concatNarration(s).split("\\s+").length).sum();
        int est = r.script().scenes().stream().mapToInt(GeneratedScript.Scene::durationSeconds).sum();

        Script script = Script.builder()
                .jobId(jobId)
                .title(r.script().title())
                .hook(r.script().hook())
                .cta(r.script().cta())
                .rawJson(r.rawJson())
                .wordCount(words)
                .estSeconds(est)
                .model(r.model())
                .promptTokens(r.promptTokens())
                .completionTokens(r.completionTokens())
                .contentHash(fp.contentHash())
                .simhash(fp.simhash())
                .variationProfile(r.profile().tag())
                .storyArc(r.storyArc())
                .regenAttempts(regenAttempts)
                .structureScore(structureScore)
                .criticScore(criticScore)
                .criticComedy(criticComedy)
                .criticEmotional(criticEmotional)
                .criticPsychology(criticPsychology)
                .build();
        script = scriptRepo.save(script);

        UUID scriptId = script.getId();
        // Collect into ArrayList so Hibernate can mutate the collection
        // during merge — Stream.toList() returns an immutable list and
        // Hibernate's merge calls clear() on it, which throws.
        List<ScriptScene> scenes = r.script().scenes().stream().map(s -> {
            String linesJson = writeJson(s.lines());
            String charsJson = writeJson(s.characters());
            return ScriptScene.builder()
                    .scriptId(scriptId)
                    .seq(s.seq())
                    .narration(concatNarration(s))
                    .visualDesc(s.visualDesc())
                    .durationSeconds(s.durationSeconds())
                    .linesJson(linesJson)
                    .charactersJson(charsJson)
                    .locationId(s.locationId())
                    .phase(s.phase())
                    .timeOfDay(s.timeOfDay())
                    .weather(s.weather())
                    .goal(s.goal())
                    .emotion(s.emotion())
                    .motionSpeed(s.motionSpeed())
                    .endPose(s.endPose())
                    .motionDesc(s.motionDesc())
                    .build();
        }).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        script.setScenes(scenes);
        scriptRepo.save(script);
    }

    private static String concatNarration(GeneratedScript.Scene s) {
        if (s.lines() == null || s.lines().isEmpty()) return "";
        return s.lines().stream().map(GeneratedScript.Line::text).collect(Collectors.joining(" "));
    }

    private String writeJson(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    @Transactional
    protected void recordDuplicateRejections(UUID jobId, int count) {
        jobRepo.findById(jobId).ifPresent(j -> {
            j.setDuplicateRejections(count);
            jobRepo.save(j);
        });
    }

    @Transactional
    protected void markStatus(UUID jobId, JobStatus status, String error) {
        jobRepo.findById(jobId).ifPresent(j -> {
            j.setStatus(status);
            j.setError(error);
            jobRepo.save(j);
        });
    }

    /**
     * Canonical SOURCE-FIX write-back. Persists orchestrator-sanitized scene
     * action text onto the stored {@link com.youtubeauto.script.domain.ScriptScene}
     * rows so a corrected accessory-vs-action contradiction stops re-appearing on
     * every downstream compile. Only non-null fields overwrite; an unknown {@code
     * seq} is skipped (a stale caller can never corrupt the store). Idempotent —
     * re-patching identical text changes nothing. Returns the refreshed script.
     */
    @Transactional
    public ScriptResponse patchScenes(UUID jobId, com.youtubeauto.script.api.dto.PatchScenesRequest req) {
        Script script = scriptRepo.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("No script for job " + jobId));
        Map<Integer, ScriptScene> bySeq = new java.util.HashMap<>();
        for (ScriptScene sc : script.getScenes()) bySeq.put(sc.getSeq(), sc);
        int changed = 0;
        for (var p : req.scenes()) {
            ScriptScene sc = bySeq.get(p.seq());
            if (sc == null) {
                log.warn("patchScenes job {}: unknown seq {} — skipped", jobId, p.seq());
                continue;
            }
            boolean dirty = false;
            if (p.visualDesc() != null && !p.visualDesc().equals(sc.getVisualDesc())) {
                sc.setVisualDesc(p.visualDesc());
                dirty = true;
            }
            if (p.motionDesc() != null && !p.motionDesc().equals(sc.getMotionDesc())) {
                sc.setMotionDesc(p.motionDesc());
                dirty = true;
            }
            if (dirty) changed++;
        }
        if (changed > 0) {
            scriptRepo.save(script);
            log.info("patchScenes job {}: source-fixed {} scene(s) (accessory-vs-action)", jobId, changed);
        }
        return get(jobId);
    }

    @Transactional(readOnly = true)
    public ScriptResponse get(UUID jobId) {
        ScriptJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown job " + jobId));
        ScriptResponse.ScriptBody body = scriptRepo.findByJobId(jobId).map(s ->
                new ScriptResponse.ScriptBody(
                        s.getId(), s.getTitle(), s.getHook(), s.getCta(),
                        s.getWordCount(), s.getEstSeconds(),
                        s.getStructureScore(), s.getCriticScore(),
                        s.getCriticComedy(), s.getCriticEmotional(), s.getCriticPsychology(),
                        s.getStoryArc(),
                        s.getScenes().stream().map(sc -> new ScriptResponse.Scene(
                                sc.getSeq(),
                                sc.getNarration(),
                                sc.getVisualDesc(),
                                sc.getDurationSeconds(),
                                readLines(sc.getLinesJson()),
                                readCharacters(sc.getCharactersJson()),
                                sc.getLocationId(),
                                sc.getPhase(),
                                sc.getTimeOfDay(),
                                sc.getWeather(),
                                sc.getGoal(),
                                sc.getEmotion(),
                                sc.getMotionSpeed(),
                                sc.getEndPose(),
                                sc.getMotionDesc()
                        )).toList()
                )
        ).orElse(null);
        return new ScriptResponse(job.getId(), job.getStatus(), job.getError(), body);
    }

    private List<ScriptResponse.Line> readLines(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var typed = mapper.getTypeFactory().constructCollectionType(List.class, ScriptResponse.Line.class);
            return mapper.readValue(json, typed);
        } catch (Exception e) {
            log.warn("Failed to read lines JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> readCharacters(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var typed = mapper.getTypeFactory().constructCollectionType(List.class, String.class);
            return mapper.readValue(json, typed);
        } catch (Exception e) {
            log.warn("Failed to read characters JSON: {}", e.getMessage());
            return List.of();
        }
    }
}
