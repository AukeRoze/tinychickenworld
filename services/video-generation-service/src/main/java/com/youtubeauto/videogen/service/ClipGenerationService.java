package com.youtubeauto.videogen.service;

import com.youtubeauto.videogen.api.dto.ClipResult;
import com.youtubeauto.videogen.api.dto.GenerateClipsRequest;
import com.youtubeauto.videogen.api.dto.GenerateClipsResponse;
import com.youtubeauto.videogen.api.dto.SceneRequest;
import com.youtubeauto.videogen.bible.BibleLoader;
import com.youtubeauto.videogen.config.GcpProperties;
import com.youtubeauto.videogen.config.VeoProperties;
import com.youtubeauto.videogen.config.WorkdirProperties;
import com.youtubeauto.videogen.cost.CostBudget;
import com.youtubeauto.videogen.cost.CostCalculator;
import com.youtubeauto.videogen.gcs.GcsClient;
import com.youtubeauto.videogen.routing.ModelRoute;
import com.youtubeauto.videogen.routing.ModelRouter;
import com.youtubeauto.videogen.routing.SceneType;
import com.youtubeauto.videogen.veo.VeoException;
import com.youtubeauto.videogen.veo.VertexVeoClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Per-job entrypoint. Fans out scenes onto a bounded executor, applies
 * cost budget + model routing, downloads + validates clips, and assembles
 * the response. One failed scene becomes FALLBACK so the job can still
 * publish via the Ken-Burns assembly path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression("'${gcp.project-id:}' != ''")
public class ClipGenerationService {

    private final ModelRouter router;
    private final CostCalculator costCalc;
    private final VertexVeoClient veo;
    private final com.youtubeauto.videogen.fal.FalSeedanceClient fal;
    private final GcsClient gcs;
    private final BibleLoader bible;
    private final VeoProperties veoProps;
    private final GcpProperties gcpProps;
    private final WorkdirProperties workdir;
    private final FrameExtractor frames;
    private final CharacterRefs characterRefs;

    public GenerateClipsResponse generate(GenerateClipsRequest req) {
        log.info("Veo generate called job={} format={} scenes={} costCap=€{}",
                req.jobId(), req.format(), req.scenes().size(),
                bible.getVideoGen().veo().costCapEurPerVideo());
        Semaphore slots = new Semaphore(veoProps.parallelism().maxParallel());
        Executor pool = Executors.newFixedThreadPool(veoProps.parallelism().maxParallel());
        CostBudget budget = new CostBudget(bible.getVideoGen().veo().costCapEurPerVideo());
        String aspect = "vertical".equalsIgnoreCase(req.format()) ? "9:16" : "16:9";

        // Frame-chaining: scenes sharing a chainGroup must render SEQUENTIALLY
        // (clip N's last frame becomes clip N+1's start image), so each chain
        // becomes ONE unit of work. Independent scenes and separate chains still
        // run in parallel against the same semaphore/budget.
        java.util.Map<Integer, List<SceneRequest>> chains = new java.util.LinkedHashMap<>();
        List<SceneRequest> solo = new ArrayList<>();
        for (SceneRequest s : req.scenes()) {
            if (s.chainGroup() != null) {
                chains.computeIfAbsent(s.chainGroup(), k -> new ArrayList<>()).add(s);
            } else {
                solo.add(s);
            }
        }
        chains.values().forEach(c -> c.sort((a, b) -> Integer.compare(a.seq(), b.seq())));

        List<CompletableFuture<List<ClipResult>>> futures = new ArrayList<>();
        for (SceneRequest s : solo) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> List.of(renderWithSlot(req.jobId(), aspect, s, budget, slots)), pool));
        }
        for (List<SceneRequest> chain : chains.values()) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> renderChain(req.jobId(), aspect, chain, budget, slots), pool));
        }

        List<ClipResult> results = new ArrayList<>();
        for (CompletableFuture<List<ClipResult>> f : futures) {
            try {
                results.addAll(f.join());
            } catch (Exception e) {
                results.add(ClipResult.failed(-1, null, "JOIN_FAILED:" + e.getMessage()));
            }
        }
        results.sort((a, b) -> Integer.compare(a.seq(), b.seq()));

        double total = results.stream().mapToDouble(ClipResult::costEur).sum();
        return new GenerateClipsResponse(req.jobId(), results, round2(total), budget.exceeded());
    }

    /** Acquires a parallelism slot around one scene render (shared by solo
     *  scenes and chain members so the global VEO_MAX_PARALLEL cap holds). */
    private ClipResult renderWithSlot(UUID jobId, String aspect, SceneRequest s,
                                      CostBudget budget, Semaphore slots) {
        try {
            slots.acquire();
            return renderScene(jobId, aspect, s, budget);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ClipResult.failed(s.seq(), null, "INTERRUPTED");
        } finally {
            slots.release();
        }
    }

    /**
     * Renders a frame-chained group sequentially. After each successful clip
     * the TRUE last frame is extracted and used as the next scene's start
     * image, so consecutive shots cut with pixel-level continuity (same pose,
     * light and framing guaranteed) instead of relying on a textual
     * "continue from the previous shot" hint. If a link fails (fallback or
     * extraction error) the chain degrades gracefully: the next scene simply
     * starts from its own original still.
     */
    private List<ClipResult> renderChain(UUID jobId, String aspect,
                                         List<SceneRequest> chain,
                                         CostBudget budget, Semaphore slots) {
        List<ClipResult> out = new ArrayList<>();
        String chainedStart = null;
        for (SceneRequest s : chain) {
            SceneRequest eff = s;
            if (chainedStart != null) {
                log.info("Scene {} chain-start: using last frame of previous clip ({})",
                        s.seq(), chainedStart);
                eff = s.withStartImage(chainedStart);
            }
            ClipResult r = renderWithSlot(jobId, aspect, eff, budget, slots);
            out.add(r);
            chainedStart = null;
            if ("OK".equals(r.status()) && r.clipPath() != null) {
                Path clip = Paths.get(r.clipPath());
                Path lastFrame = frames.extractLastFrame(
                        clip, clip.getParent().resolve(FrameExtractor.LAST_FRAME));
                if (lastFrame != null) chainedStart = lastFrame.toString();
                else log.warn("Scene {} chain broken — last-frame extraction failed, "
                        + "next scene starts from its own still", s.seq());
            } else {
                log.warn("Scene {} chain broken — status {} , next scene starts from "
                        + "its own still", s.seq(), r.status());
            }
        }
        return out;
    }

    private ClipResult renderScene(UUID jobId, String aspect, SceneRequest s, CostBudget budget) {
        SceneType type = SceneType.parse(s.sceneType());
        log.info("Scene {} START type={} startImg={} dur={}s",
                s.seq(), type, s.startImagePath(), s.durationSeconds());

        if (budget.exceeded()) {
            log.warn("Scene {} COST_CAP — budget already exceeded", s.seq());
            return ClipResult.fallback(s.seq(), router.pick(type, s.durationSeconds(), true).modelId(), "COST_CAP");
        }

        ModelRoute route = router.pick(type, s.durationSeconds(), budget.nearby(), s.modelOverride());
        double estimate = costCalc.estimate(route);
        log.info("Scene {} route model={} resolution={} dur={}s estimate=€{}",
                s.seq(), route.modelId(), route.resolution(), route.durationSec(), estimate);

        if (budget.spent() + estimate > budget.cap()) {
            // DOWNSHIFT before giving up: a Veo Lite clip still MOVES — a Ken
            // Burns still does not, and the moving/frozen scene mix was the
            // most visible quality break in the ep-2 audit. Only when even
            // Lite doesn't fit does the scene fall back to Ken Burns.
            ModelRoute lite = router.cheapest(s.durationSeconds());
            double liteEst = costCalc.estimate(lite);
            if (budget.spent() + liteEst <= budget.cap()) {
                log.warn("Scene {} COST_CAP_DOWNSHIFT — {} (€{}) doesn't fit, using {} (€{})",
                        s.seq(), route.modelId(), estimate, lite.modelId(), liteEst);
                route = lite;
                estimate = liteEst;
            } else {
                log.warn("Scene {} COST_CAP_PREEMPTIVE — spent=€{} + est=€{} > cap=€{}",
                        s.seq(), budget.spent(), estimate, budget.cap());
                return ClipResult.fallback(s.seq(), route.modelId(), "COST_CAP_PREEMPTIVE");
            }
        }

        Path workScene = workdirScene(jobId, s.seq());
        Path startImg = Paths.get(s.startImagePath());
        Path clipOut = workScene.resolve("clip.mp4");

        long start = System.currentTimeMillis();
        try {
            Files.createDirectories(workScene);

            // ── Second provider: Seedance 2.0 via fal.ai ──────────────────
            // Same in/out contract as Veo (start image + prompt → clip.mp4);
            // no GCS round-trip (images travel as data URIs). Any IOException
            // falls through to the existing FALLBACK handling below.
            if (route.modelId().startsWith("bytedance/")) {
                Path endImg = (s.endImagePath() != null && !s.endImagePath().isBlank())
                        ? Paths.get(s.endImagePath()) : null;
                log.info("Scene {} calling fal Seedance (model={}, endFrame={})",
                        s.seq(), route.modelId(), endImg != null);
                fal.generateAndDownload(route.modelId(), s.visualDesc(), startImg, endImg,
                        route.resolution(), route.durationSec(), aspect, clipOut);
                if (!isValidMp4(clipOut)) {
                    log.warn("Scene {} CORRUPT_OUTPUT (seedance)", s.seq());
                    return ClipResult.fallback(s.seq(), route.modelId(), "CORRUPT_OUTPUT");
                }
                frames.extractQcFrames(clipOut, workScene);
                budget.add(estimate);
                long wallFal = System.currentTimeMillis() - start;
                log.info("Scene {} OK: model={} dur={}s wall={}ms cost≈€{}",
                        s.seq(), route.modelId(), route.durationSec(), wallFal, estimate);
                return ClipResult.ok(s.seq(), clipOut.toString(), route.modelId(),
                        route.resolution(), route.durationSec(), wallFal, round2(estimate));
            }

            log.info("Scene {} uploading start image to GCS", s.seq());

            String startGcs = gcs.uploadImage(jobId, s.seq(), startImg, "image/png");
            String endGcs   = uploadEndIfPresent(jobId, s);
            String outGcs   = gcs.outputPrefixUri(jobId, s.seq());
            log.info("Scene {} calling Veo (model={}, endFrame={})",
                    s.seq(), route.modelId(), endGcs != null);

            // Character reference stills (audit #5): identity anchored in pixels.
            List<Path> refs = characterRefs.resolve(s.characters());
            String resultUri = veo.generateAndAwait(
                    route, s.visualDesc(), startGcs, endGcs, s.negativePrompt(), aspect,
                    outGcs, refs);
            log.info("Scene {} Veo returned {} (refs={})", s.seq(), resultUri, refs.size());

            gcs.download(resultUri, clipOut);
            if (!isValidMp4(clipOut)) {
                gcs.deleteQuietly(resultUri);
                log.warn("Scene {} CORRUPT_OUTPUT", s.seq());
                return ClipResult.fallback(s.seq(), route.modelId(), "CORRUPT_OUTPUT");
            }
            frames.extractQcFrames(clipOut, workScene);

            budget.add(estimate);
            long wall = System.currentTimeMillis() - start;
            log.info("Scene {} OK: model={} dur={}s wall={}ms cost≈€{}",
                    s.seq(), route.modelId(), route.durationSec(), wall, estimate);
            return ClipResult.ok(s.seq(), clipOut.toString(), route.modelId(),
                    route.resolution(), route.durationSec(), wall, round2(estimate));

        } catch (VeoException ve) {
            log.warn("Scene {} Veo error ({}): {}", s.seq(), ve.kind(), ve.getMessage());
            if (ve.kind() == VeoException.Kind.QUOTA) {
                return retryQuotaWithBackoff(jobId, aspect, s, route, budget, ve, start);
            }
            return ClipResult.fallback(s.seq(), route.modelId(), ve.kind().name());
        } catch (IOException io) {
            log.warn("Scene {} IO error: {}", s.seq(), io.getMessage());
            return ClipResult.fallback(s.seq(), route.modelId(), "IO:" + io.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ClipResult.failed(s.seq(), route.modelId(), "INTERRUPTED");
        } catch (RuntimeException e) {
            // Catches StorageException, IllegalArgumentException etc. that
            // were silently leaking before — root cause for "Veo done €0".
            log.error("Scene {} UNCAUGHT RUNTIME: {} message={}",
                    s.seq(), e.getClass().getSimpleName(), e.getMessage(), e);
            return ClipResult.fallback(s.seq(), route.modelId(),
                    "RUNTIME:" + e.getClass().getSimpleName() + ":" + e.getMessage());
        }
    }

    /**
     * Quota (RESOURCE_EXHAUSTED) backoff. A Vertex quota / rate-limit hit is
     * transient: instead of dumping the scene straight to Ken Burns (€0, frozen
     * frame) we retry the SAME model with bounded exponential backoff + jitter
     * (5s, 15s, 45s by default). The call runs on a pool thread holding its
     * parallelism semaphore slot, so sleeping here also throttles overall
     * throughput while Vertex is under quota pressure — exactly the backpressure
     * we want. Only after the retries are exhausted (or another, non-quota error
     * surfaces) do we degrade to the existing fallback/Ken-Burns path.
     */
    private ClipResult retryQuotaWithBackoff(UUID jobId, String aspect, SceneRequest s,
                                             ModelRoute route, CostBudget budget,
                                             VeoException original, long started) {
        int maxRetries = veoProps.quota().maxRetries();
        long baseMs = veoProps.quota().baseBackoffMs();
        VeoException last = original;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // Exponential backoff: base * 3^(attempt-1) → 5s, 15s, 45s for base=5000.
            long backoff = (long) (baseMs * Math.pow(3, attempt - 1));
            long jitter = java.util.concurrent.ThreadLocalRandom.current()
                    .nextLong(0, Math.max(1, backoff / 4));   // up to +25% jitter
            long sleepMs = backoff + jitter;
            log.warn("Scene {} QUOTA — backing off {}s (attempt {}/{}) on model {}",
                    s.seq(), Math.round(sleepMs / 1000.0), attempt, maxRetries, route.modelId());
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Scene {} QUOTA backoff interrupted", s.seq());
                return ClipResult.failed(s.seq(), route.modelId(), "INTERRUPTED");
            }

            double estimate = costCalc.estimate(route);
            if (budget.spent() + estimate > budget.cap()) {
                log.warn("Scene {} QUOTA — budget no longer fits same-model retry, "
                        + "falling back", s.seq());
                break;
            }
            Path workScene = workdirScene(jobId, s.seq());
            Path clipOut = workScene.resolve("clip.mp4");
            try {
                Files.createDirectories(workScene);
                String startGcs = gcs.uploadImage(jobId, s.seq(),
                        Paths.get(s.startImagePath()), "image/png");
                String endGcs   = uploadEndIfPresent(jobId, s);
                String outGcs   = gcs.outputPrefixUri(jobId, s.seq());
                String resultUri = veo.generateAndAwait(
                        route, s.visualDesc(), startGcs, endGcs, s.negativePrompt(), aspect,
                        outGcs, characterRefs.resolve(s.characters()));
                gcs.download(resultUri, clipOut);
                if (!isValidMp4(clipOut)) {
                    gcs.deleteQuietly(resultUri);
                    log.warn("Scene {} CORRUPT_OUTPUT after QUOTA retry", s.seq());
                    return ClipResult.fallback(s.seq(), route.modelId(), "CORRUPT_OUTPUT");
                }
                frames.extractQcFrames(clipOut, workScene);
                budget.add(estimate);
                long wall = System.currentTimeMillis() - started;
                log.info("Scene {} OK after QUOTA retry {}/{}: model={} dur={}s wall={}ms cost≈€{}",
                        s.seq(), attempt, maxRetries, route.modelId(), route.durationSec(),
                        wall, estimate);
                return ClipResult.ok(s.seq(), clipOut.toString(), route.modelId(),
                        route.resolution(), route.durationSec(), wall, round2(estimate));
            } catch (VeoException ve) {
                if (ve.kind() == VeoException.Kind.QUOTA) {
                    last = ve;   // still quota — keep backing off
                    continue;
                }
                log.warn("Scene {} QUOTA retry hit non-quota error ({}): {} — falling back",
                        s.seq(), ve.kind(), ve.getMessage());
                break;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return ClipResult.failed(s.seq(), route.modelId(), "INTERRUPTED");
            } catch (Exception ex) {
                log.warn("Scene {} QUOTA retry error ({}): {} — falling back",
                        s.seq(), ex.getClass().getSimpleName(), ex.getMessage());
                break;
            }
        }
        // Retries exhausted (or budget/other error): degrade to the existing
        // downshift + Ken-Burns fallback path.
        log.warn("Scene {} QUOTA retries exhausted ({} attempts) — degrading to fallback",
                s.seq(), maxRetries);
        return retryOnFallback(jobId, aspect, s, budget, last, started);
    }

    private ClipResult retryOnFallback(UUID jobId, String aspect, SceneRequest s,
                                       CostBudget budget, VeoException original, long started) {
        ModelRoute fb = router.fallback(s.durationSeconds());
        double estimate = costCalc.estimate(fb);
        if (budget.spent() + estimate > budget.cap()) {
            return ClipResult.fallback(s.seq(), fb.modelId(), "COST_CAP_AFTER_QUOTA");
        }
        Path workScene = workdirScene(jobId, s.seq());
        Path clipOut = workScene.resolve("clip.mp4");
        try {
            String startGcs = gcs.uploadImage(jobId, s.seq(), Paths.get(s.startImagePath()), "image/png");
            String endGcs   = uploadEndIfPresent(jobId, s);
            String outGcs   = gcs.outputPrefixUri(jobId, s.seq());
            String resultUri = veo.generateAndAwait(
                    fb, s.visualDesc(), startGcs, endGcs, s.negativePrompt(), aspect, outGcs,
                    characterRefs.resolve(s.characters()));
            gcs.download(resultUri, clipOut);
            if (!isValidMp4(clipOut)) return ClipResult.fallback(s.seq(), fb.modelId(), "CORRUPT_OUTPUT");
            frames.extractQcFrames(clipOut, workScene);
            budget.add(estimate);
            long wall = System.currentTimeMillis() - started;
            return ClipResult.ok(s.seq(), clipOut.toString(), fb.modelId(),
                    fb.resolution(), fb.durationSec(), wall, round2(estimate));
        } catch (Exception ex) {
            return ClipResult.fallback(s.seq(), fb.modelId(),
                    "QUOTA_THEN_" + (ex instanceof VeoException ve ? ve.kind().name() : ex.getClass().getSimpleName()));
        }
    }

    /** Uploads the scene's end-pose still to GCS if it exists, else null. The
     *  end frame lets Veo interpolate a directed start→end motion. Best-effort:
     *  a missing/unreadable end image just falls back to start-only. */
    private String uploadEndIfPresent(UUID jobId, SceneRequest s) {
        String ep = s.endImagePath();
        if (ep == null || ep.isBlank()) return null;
        try {
            Path endImg = Paths.get(ep);
            if (!Files.exists(endImg)) {
                log.warn("Scene {} endImagePath {} not found — start-only", s.seq(), ep);
                return null;
            }
            return gcs.uploadEndImage(jobId, s.seq(), endImg, "image/png");
        } catch (Exception e) {
            log.warn("Scene {} end-image upload failed ({}), continuing start-only", s.seq(), e.getMessage());
            return null;
        }
    }

    private Path workdirScene(UUID jobId, int seq) {
        return Paths.get(workdir.root(), "jobs", jobId.toString(), "scenes", String.valueOf(seq));
    }

    private boolean isValidMp4(Path mp4) {
        try {
            if (!Files.exists(mp4) || Files.size(mp4) < 1024) return false;
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=codec_name", mp4.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
