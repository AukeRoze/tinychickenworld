package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the lean-prompt fixes in {@link VeoPromptCompiler}: compact
 * dna.veoKey identity, the relaxed cast lock (no "fully visible first→last frame"
 * contradiction) and the single veoScaleRule. All gated behind veoLeanPrompts;
 * with the flag off the output keeps the verbose/legacy wording.
 */
class VeoPromptCompilerLeanTest {

    private static final String CAMERA =
            "cameraBible:\n"
          + "  default: { angle: \"eye-level\", lens: \"50mm\", movement: \"slow move\", focus: \"f\", depthOfField: \"d\" }\n"
          + "  climax: { angle: \"low angle\", lens: \"85mm long lens\", movement: \"slow push-in toward the peak\", focus: \"lock focus on the main character's face\", depthOfField: \"shallow\" }\n"
          + "  resolution: { angle: \"eye-level\", lens: \"50mm\", movement: \"calm drift\", focus: \"soft focus on the flock together\", depthOfField: \"medium\" }\n"
          + "  setup: { angle: \"eye-level\", lens: \"35mm\", movement: \"slow drift\", focus: \"main character sharp\", depthOfField: \"medium\" }\n";

    private static final String CHARS =
            "characters:\n"
          + "  - id: pip\n"
          + "    name: \"Pip\"\n"
          + "    dna:\n"
          + "      coreColor: \"cream-white\"\n"
          + "      veoKey: \"PIP_VEOKEY\"\n"
          + "      accessory: \"straw hat\"\n"
          + "      silhouette: \"VERBOSE_SILHOUETTE_TEXT\"\n"
          + "  - id: mo\n"
          + "    name: \"Mo\"\n"
          + "    dna:\n"
          + "      coreColor: \"blue-grey\"\n"
          + "      veoKey: \"MO_VEOKEY\"\n"
          + "      accessory: \"red scarf\"\n"
          + "      silhouette: \"MO_SILHOUETTE_TEXT\"\n";

    private VeoPromptCompiler compilerFor(Path dir, boolean lean) throws Exception {
        Path bible = dir.resolve("channel.yml");
        String yml = (lean
                ? "veoLeanPrompts: true\nveoScaleRule: \"SCALE_RULE_TEXT\"\n"
                : "") + CAMERA + CHARS;
        Files.writeString(bible, yml);
        OrchestratorProperties props = new OrchestratorProperties(
                new OrchestratorProperties.Services("", "", "", "", "", "", ""),
                new OrchestratorProperties.Poll(10, 5),
                new OrchestratorProperties.Anthropic(null, null, null, "claude-test", null, null),
                new OrchestratorProperties.Defaults("preschoolers", 60, false, "ken_burns"),
                new OrchestratorProperties.Brand("", ""),
                new OrchestratorProperties.Bible(bible.toString()));
        return new VeoPromptCompiler(props);
    }

    @Test
    void leanMode_usesCompactIdentityRelaxedLockAndScaleRule(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerFor(tmp, true);
        String out = c.compile("Pip peeks up from the bottom edge", "setup",
                List.of("pip"), "", "", "", "", "", "natural");
        assertTrue(out.contains("PIP_VEOKEY"), "compact veoKey identity should be used");
        assertFalse(out.contains("VERBOSE_SILHOUETTE_TEXT"),
                "verbose DNA fields should be dropped in lean mode");
        assertTrue(out.contains("match the start frame"),
                "identity should point at the reference start frame");
        assertTrue(out.contains("Cast lock:"), "relaxed cast lock wording expected");
        assertFalse(out.contains("fully visible from the"),
                "the full-visibility contradiction must be gone in lean mode");
        assertTrue(out.contains("Relative size: SCALE_RULE_TEXT"),
                "the single scale rule should be used");
    }

    @Test
    void leanMode_offFrameCastMemberIsNotForcedIntoShot(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerFor(tmp, true);
        // Two-shot cast for continuity, but the action frames only Pip — Mo is
        // present yet unframed (insert/participation beat). The lock must keep the
        // count (anti-newcomer/anti-swap) without cramming a motionless Mo in.
        String out = c.compile("Close on Pip tucking the straw, beak open in delight",
                "development", List.of("pip", "mo"), "", "", "", "", "", "natural");
        assertTrue(out.contains("EXACTLY 2 characters"), "count must stay pinned, got: " + out);
        assertTrue(out.contains("NOT framed in this shot"),
                "the off-frame member must be flagged as not framed, got: " + out);
        assertTrue(out.contains("Mo"), "the off-frame member should be named, got: " + out);
    }

    @Test
    void leanMode_visualDescCloseUpRelaxesEvenWhenPhaseCameraIsWide(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerFor(tmp, true);
        // Phase camera default is a 50mm medium, but the action frames an extreme
        // close-up on a detail — the tight-shot relaxation should still fire so the
        // cast lock does not demand whole bodies in a detail shot.
        String out = c.compile("Extreme close-up of the wings and the egg on the ground",
                "development", List.of("pip", "mo"), "", "", "", "", "", "natural");
        assertTrue(out.contains("tight shot"),
                "a close-up stated in the action must relax the lock, got: " + out);
    }

    @Test
    void compilerOutputAlwaysPassesTheLinter(@TempDir Path tmp) throws Exception {
        // Regression net: whatever the compiler emits must satisfy the
        // deterministic prompt invariants (no truncation, all sections present,
        // cast-lock count == cast size). A future compiler change that drops a
        // section or mis-counts the cast trips this — the gap that let the
        // EP3 truncations through unnoticed.
        VeoPromptCompiler c = compilerFor(tmp, true);
        String solo = c.compile("Pip peeks up from the bottom edge", "setup",
                List.of("pip"), "loc", "midday", "clear", "goal", "wonder (5/5)", "natural");
        assertTrue(VeoPromptLinter.isHealthy(solo, 1),
                "single-cast prompt must pass the linter: " + VeoPromptLinter.lint(solo, 1));
        String two = c.compile("Close on Pip tucking the straw while Mo waits", "development",
                List.of("pip", "mo"), "loc", "midday", "clear", "goal", "joy (3/5)", "natural");
        assertTrue(VeoPromptLinter.isHealthy(two, 2),
                "two-cast prompt must pass the linter: " + VeoPromptLinter.lint(two, 2));
    }

    @Test
    void shotAwareCamera_wideRevealOverridesPushInPreset(@TempDir Path tmp) throws Exception {
        // A wide reveal stated in the action must override the climax push-in
        // preset (the scene-22/29 defect) — no push-in, no long lens.
        VeoPromptCompiler c = compilerFor(tmp, true);
        String out = c.compile("Wide shot of the trio as the camera pulls back to reveal the full hilltop",
                "climax", List.of("pip"), "loc", "midday", "clear", "goal", "joy (5/5)", "natural");
        assertTrue(out.contains("widens the frame") || out.contains("pull-back"),
                "a wide reveal must replace the push-in with a widening move: " + out);
        assertFalse(out.contains("push-in toward the peak"),
                "the climax push-in preset must be gone on a wide reveal: " + out);
        assertFalse(out.contains("85mm"),
                "the long lens should widen on a reveal: " + out);
    }

    @Test
    void shotAwareCamera_closeUpDropsFlockFocus(@TempDir Path tmp) throws Exception {
        // A solo close-up must not inherit the resolution preset's flock focus
        // (the scene-27 defect).
        VeoPromptCompiler c = compilerFor(tmp, true);
        String out = c.compile("Close-up of Pip looking directly at the camera",
                "resolution", List.of("pip"), "loc", "midday", "clear", "goal", "warm (3/5)", "natural");
        assertFalse(out.contains("flock together"),
                "a close-up must not focus on the whole flock: " + out);
        assertTrue(out.contains("framed subject sharp"),
                "focus should move to the framed subject: " + out);
    }

    @Test
    void shotAwareCamera_normalSceneKeepsPresetVerbatim(@TempDir Path tmp) throws Exception {
        // No wide/close-up signal → the preset is emitted unchanged (no regression).
        VeoPromptCompiler c = compilerFor(tmp, true);
        String out = c.compile("Pip counts down with one raised wing", "climax",
                List.of("pip"), "loc", "midday", "clear", "goal", "anticipation (4/5)", "natural");
        assertTrue(out.contains("slow push-in toward the peak"),
                "an ordinary climax beat must keep the push-in preset: " + out);
    }

    @Test
    void shotAwareCamera_closeUpNarrowsWideLensTo50mm(@TempDir Path tmp) throws Exception {
        // The setup preset is a 35mm wide; a close-up must narrow it to a 50mm
        // portrait so the face doesn't bulge (GoPro/fisheye) — scene-3.
        VeoPromptCompiler c = compilerFor(tmp, true);
        String out = c.compile("Close-up of Pip tipping her straw hat", "setup",
                List.of("pip"), "loc", "midday", "clear", "goal", "joy (3/5)", "natural");
        assertTrue(out.contains("50mm portrait"), "a close-up should use a 50mm portrait lens: " + out);
        assertFalse(out.contains("35mm"), "the 35mm wide preset must be narrowed on a close-up: " + out);
    }

    @Test
    void shotAwareSetting_closeUpUsesBokehBackground(@TempDir Path tmp) throws Exception {
        // A macro/close-up must not dump the full wide-shot location detail into the
        // Setting (the scene-1 over-stuffing defect) — it reads as soft bokeh.
        VeoPromptCompiler c = compilerFor(tmp, true);
        String out = c.compile("Extreme close-up of the egg in the dark soil", "hook",
                List.of("pip"), "garden", "morning", "clear", "goal", "wonder (5/5)", "natural");
        assertTrue(out.contains("creamy bokeh"),
                "a close-up setting should be soft bokeh background: " + out);
        assertTrue(out.contains("early-morning light"),
                "the time-of-day light must still be present: " + out);
    }

    @Test
    void legacyMode_keepsVerboseWording(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerFor(tmp, false);
        String out = c.compile("Pip peeks up from the bottom edge", "setup",
                List.of("pip"), "", "", "", "", "", "natural");
        assertTrue(out.contains("Headcount lock:"), "legacy headcount lock kept when flag off");
        assertTrue(out.contains("fully visible from the"), "legacy full-visibility wording kept");
        assertFalse(out.contains("PIP_VEOKEY"),
                "compact veoKey must NOT be used when lean mode is off");
    }

    // ---- Flow / Veo 3.1 native audio ----------------------------------------

    /** Builds a compiler whose bible enables veoNativeAudio and gives each
     *  character a dna.signatureSound (the wordless chick "voice"). */
    private VeoPromptCompiler nativeAudioCompilerFor(Path dir) throws Exception {
        Path bible = dir.resolve("channel.yml");
        String chars =
                "characters:\n"
              + "  - id: pip\n    name: \"Pip\"\n    dna:\n"
              + "      coreColor: \"cream-white\"\n      veoKey: \"PIP_VEOKEY\"\n"
              + "      signatureSound: \"a bright rising curious chirp\"\n"
              + "  - id: mo\n    name: \"Mo\"\n    dna:\n"
              + "      coreColor: \"blue-grey\"\n      veoKey: \"MO_VEOKEY\"\n"
              + "      signatureSound: \"a soft low thoughtful hum\"\n";
        String yml = "veoLeanPrompts: true\nveoNativeAudio: true\nveoScaleRule: \"SCALE_RULE_TEXT\"\n"
                + CAMERA + chars;
        Files.writeString(bible, yml);
        OrchestratorProperties props = new OrchestratorProperties(
                new OrchestratorProperties.Services("", "", "", "", "", "", ""),
                new OrchestratorProperties.Poll(10, 5),
                new OrchestratorProperties.Anthropic(null, null, null, "claude-test", null, null),
                new OrchestratorProperties.Defaults("preschoolers", 60, false, "ken_burns"),
                new OrchestratorProperties.Brand("", ""),
                new OrchestratorProperties.Bible(bible.toString()));
        return new VeoPromptCompiler(props);
    }

    @Test
    void nativeAudio_directorBriefFormat_wordlessWhenNoLines(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = nativeAudioCompilerFor(tmp);
        String out = c.compile("Pip peeks up from the bottom edge", "setup",
                List.of("pip"), "pond", "midday", "clear", "goal", "wonder", "natural",
                null, null, "energetic adventure");
        // Structured director's-brief sections.
        assertTrue(out.contains("DIRECTOR'S BRIEF & ENVIRONMENT"), "brief header expected: " + out);
        assertTrue(out.contains("CHARACTER ROSTER (STRICT LIMIT: EXACTLY 1 CHICKEN)"), "strict roster expected: " + out);
        assertTrue(out.contains("CHRONOLOGICAL ACTION"), "action section expected: " + out);
        assertTrue(out.contains("Camera Setup:") && out.contains("Shot Type:"), "camera/shot sections expected: " + out);
        // No spoken lines -> wordless chick voice driven by the signature sound.
        assertTrue(out.contains("a bright rising curious chirp"), "Pip signature sound expected: " + out);
        assertTrue(out.contains("NO human words"), "wordless direction expected when there are no lines: " + out);
        // Music out; the strengthened anti-extra-chicken rule is present.
        assertTrue(out.contains("No background music"), "music must be out (added in post): " + out);
        assertFalse(out.contains("plucky pizzicato"), "no per-clip music score: " + out);
        assertTrue(out.contains("no extra chickens anywhere in the scene"), "anti-extra-chicken rule expected: " + out);
        assertTrue(VeoPromptLinter.isHealthy(out, 1),
                "director's-brief prompt must pass the linter: " + VeoPromptLinter.lint(out, 1));
    }

    @Test
    void nativeAudio_offByDefault_noAudioBlock(@TempDir Path tmp) throws Exception {
        // The lean compiler (no veoNativeAudio key) must NOT emit an audio block,
        // and must keep the legacy no-lip-sync clause — byte-compatible behaviour.
        VeoPromptCompiler c = compilerFor(tmp, true);
        String out = c.compile("Pip peeks up from the bottom edge", "setup",
                List.of("pip"), "pond", "midday", "clear", "goal", "wonder", "natural",
                null, null, "energetic");
        assertFalse(out.contains("Audio (generate natively"),
                "no audio block when veoNativeAudio is absent: " + out);
        assertTrue(out.contains("do NOT lip-sync"),
                "legacy no-lip-sync clause kept when native audio is off: " + out);
    }

    @Test
    void nativeAudio_spokenLinesBecomeEnglishLipSyncedDialogue(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = nativeAudioCompilerFor(tmp);
        java.util.Map<String, Object> l1 = new java.util.HashMap<>();
        l1.put("speaker", "pip"); l1.put("text", "Hi friends! I found an egg!");
        java.util.Map<String, Object> l2 = new java.util.HashMap<>();
        l2.put("speaker", "mo"); l2.put("text", "It feels cold.");
        String out = c.compile("Pip greets the viewer beside the egg while Mo looks on", "setup",
                List.of("pip", "mo"), "garden", "midday", "clear", "goal", "curious", "natural",
                null, null, null, List.of(l1, l2));
        // The actual script lines become quoted, attributed speech.
        assertTrue(out.contains("Pip says, \"Hi friends! I found an egg!\""),
                "Pip's line should be quoted, attributed speech: " + out);
        assertTrue(out.contains("Mo says, \"It feels cold.\""),
                "Mo's line should be quoted, attributed speech: " + out);
        // English is locked and the speaking beak lip-syncs the words.
        assertTrue(out.contains("ENGLISH"), "English must be enforced: " + out);
        assertTrue(out.contains("lip-sync"), "the talking character should lip-sync the words: " + out);
        // No wordless framing and no music when there are spoken lines.
        assertFalse(out.contains("NO human words"),
                "the wordless 'NO human words' direction must be gone when speaking: " + out);
        assertTrue(out.contains("No background music"), "music stays out (post): " + out);
        // Director's-brief structure with the strict 2-chicken roster.
        assertTrue(out.contains("CHARACTER ROSTER (STRICT LIMIT: EXACTLY 2 CHICKENS)"),
                "strict 2-chicken roster expected: " + out);
        assertTrue(out.contains("no extra chickens anywhere in the scene"),
                "anti-extra-chicken rule expected: " + out);
        assertTrue(VeoPromptLinter.isHealthy(out, 2),
                "dialogue prompt must pass the linter: " + VeoPromptLinter.lint(out, 2));
    }

    @Test
    void nativeAudio_customNegativeConstraintsFromSidecarOverrideDefaults(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = nativeAudioCompilerFor(tmp);
        // A sidecar negative-constraints.txt next to the bible replaces the
        // built-in tail (comments / blank lines ignored). The cast-size
        // anti-duplication clause stays programmatic and must remain.
        Files.writeString(tmp.resolve("negative-constraints.txt"),
                "# managed from the Brand page\n"
              + "No drones or modern technology\n"
              + "\n"
              + "Keep the barn door closed\n");
        String out = c.compile("Pip peeks up from the bottom edge", "setup",
                List.of("pip"), "pond", "midday", "clear", "goal", "wonder", "natural",
                null, null, "energetic adventure");
        assertTrue(out.contains("No drones or modern technology"),
                "custom constraint should be injected: " + out);
        assertTrue(out.contains("Keep the barn door closed"),
                "custom constraint should be injected: " + out);
        assertFalse(out.contains("Pip's straw hat must never come off"),
                "the built-in default tail must be replaced by the custom list: " + out);
        assertTrue(out.contains("no extra chickens anywhere in the scene"),
                "the programmatic anti-extra-chicken core must remain: " + out);
        assertTrue(VeoPromptLinter.isHealthy(out, 1),
                "custom-constraints prompt must pass the linter: " + VeoPromptLinter.lint(out, 1));
    }

    /** Bible with the full cast: 3 chickens + 1 duckling (species/rosterNoun) and
     *  a dna.tic per character, plus veoNativeAudio — for the roster-count and
     *  one-signature-per-clip checks. */
    private VeoPromptCompiler castCompilerFor(Path dir) throws Exception {
        Path bible = dir.resolve("channel.yml");
        String chars =
                "characters:\n"
              + "  - id: pip\n    name: \"Pip\"\n    species: chicken\n    dna:\n"
              + "      coreColor: \"cream-white\"\n      veoKey: \"PIP_VEOKEY\"\n"
              + "      tic: \"taps the brim of her straw hat\"\n"
              + "  - id: mo\n    name: \"Mo\"\n    species: chicken\n    dna:\n"
              + "      coreColor: \"blue-grey\"\n      veoKey: \"MO_VEOKEY\"\n"
              + "      tic: \"tugs his red scarf\"\n"
              + "  - id: bo\n    name: \"Bo\"\n    species: chicken\n    dna:\n"
              + "      coreColor: \"tan\"\n      veoKey: \"BO_VEOKEY\"\n"
              + "      tic: \"pushes her glasses up\"\n"
              + "  - id: duckling\n    name: \"Duckling\"\n    species: duck\n    rosterNoun: \"duckling\"\n    dna:\n"
              + "      coreColor: \"lemon-yellow\"\n      veoKey: \"DUCK_VEOKEY\"\n"
              + "      tic: \"flaps his tiny wings\"\n";
        String yml = "veoLeanPrompts: true\nveoNativeAudio: true\nveoScaleRule: \"SCALE_RULE_TEXT\"\n"
                + CAMERA + chars;
        Files.writeString(bible, yml);
        OrchestratorProperties props = new OrchestratorProperties(
                new OrchestratorProperties.Services("", "", "", "", "", "", ""),
                new OrchestratorProperties.Poll(10, 5),
                new OrchestratorProperties.Anthropic(null, null, null, "claude-test", null, null),
                new OrchestratorProperties.Defaults("preschoolers", 60, false, "ken_burns"),
                new OrchestratorProperties.Brand("", ""),
                new OrchestratorProperties.Bible(bible.toString()));
        return new VeoPromptCompiler(props);
    }

    @Test
    void roster_mixedSpeciesCountsChickensAndDucklingSeparately(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = castCompilerFor(tmp);
        String out = c.compile("The flock gathers near the egg", "development",
                List.of("pip", "mo", "bo", "duckling"), "garden", "midday", "clear",
                "goal", "joy", "natural", null, null, "energetic adventure");
        // The duckling must NOT be counted as a 4th chicken (the EP23-29 bug).
        assertTrue(out.contains("EXACTLY 3 CHICKENS AND 1 DUCKLING (TOTAL 4 CHARACTERS)"),
                "species-aware roster expected: " + out);
        assertFalse(out.contains("EXACTLY 4 CHICKENS"),
                "must never ask for 4 chickens when one is a duckling: " + out);
        assertTrue(out.contains("no fourth chicken"), "per-species chicken cap expected: " + out);
        assertTrue(out.contains("no extra ducklings") && out.contains("no second duckling"),
                "per-species duckling cap expected: " + out);
        // The linter reads the TOTAL (4), not the per-species "3".
        assertTrue(VeoPromptLinter.isHealthy(out, 4),
                "mixed-cast prompt must pass the linter at TOTAL 4: " + VeoPromptLinter.lint(out, 4));
    }

    @Test
    void action_onlyOneSignatureGesturePerClip(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = castCompilerFor(tmp);
        String out = c.compile("The flock reacts to the egg", "development",
                List.of("pip", "mo", "bo"), "garden", "midday", "clear",
                "goal", "joy", "natural", null, null, "energetic");
        assertTrue(out.contains("Signature character motion (ONE per clip)"),
                "one-signature-per-clip header expected: " + out);
        assertTrue(out.contains("Only Pip performs a signature gesture this clip"),
                "only the lead should perform a gesture: " + out);
        // The non-lead tics must NOT both fire in one clip (the morphing overload).
        assertFalse(out.contains("tugs his red scarf") && out.contains("pushes her glasses up"),
                "the other characters' tics must not fire simultaneously: " + out);
        assertTrue(VeoPromptLinter.isHealthy(out, 3),
                "prompt must pass the linter: " + VeoPromptLinter.lint(out, 3));
    }

    @Test
    void solo_dialogueDoesNotReferenceAbsentOthers(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = castCompilerFor(tmp);
        java.util.Map<String, Object> l = new java.util.HashMap<>();
        l.put("speaker", "pip"); l.put("text", "Look what I found!");
        String out = c.compile("Pip talks to the camera", "setup",
                List.of("pip"), "garden", "midday", "clear", "goal", "joy", "natural",
                null, null, null, List.of(l));
        // No "the others" reference in a single-chicken scene — that wording made
        // the AI invent the missing chickens in the background.
        assertFalse(out.contains("while the others keep their beaks closed"),
                "a solo scene must not reference absent other chickens: " + out);
        assertTrue(out.contains("ENGLISH") && out.contains("lip-sync"),
                "solo speech is still English + lip-synced: " + out);
        assertTrue(out.contains("EXACTLY 1 CHICKEN"), "solo roster expected: " + out);
        assertTrue(VeoPromptLinter.isHealthy(out, 1),
                "solo prompt must pass the linter: " + VeoPromptLinter.lint(out, 1));
    }

    @Test
    void neutralisesFlockCountWordsForReducedCast() {
        // The per-character identity line must not imply a fixed flock size in a
        // reduced-cast scene (the "of the trio" weight-bleed).
        assertEquals("the smallest chick of the flock",
                VeoPromptCompiler.neutraliseCountWords("the smallest chick of the trio"));
        assertEquals("slightly the largest of the flock",
                VeoPromptCompiler.neutraliseCountWords("slightly the largest of the three"));
        assertEquals("the tiniest of the flock",
                VeoPromptCompiler.neutraliseCountWords("the tiniest of the four"));
    }

    @Test
    void singleSpeakerNamesTheOneSilentChickNotPluralChicks(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = castCompilerFor(tmp);
        java.util.Map<String, Object> l = new java.util.HashMap<>();
        l.put("speaker", "pip"); l.put("text", "Can you tok tok too?");
        // EP3 scene-18: roster is exactly 2 chicks (Pip speaks, Mo is silent).
        String out = c.compile("Pip turns to Mo and taps a gentle rhythm", "development",
                List.of("pip", "mo"), "garden", "midday", "clear", "goal", "joy", "natural",
                null, null, null, List.of(l));
        // The silent chick must be NAMED in the singular — never the generic plural
        // "the non-speaking chicks", which made the model hunt for an absent 3rd chick.
        assertFalse(out.contains("non-speaking chicks"),
                "must not use the generic plural for a single silent chick: " + out);
        assertTrue(out.contains("Mo keeps its beak closed"),
                "the one silent chick must be named in the singular: " + out);
        // Signature-gesture line: singular subject → singular verb.
        assertFalse(out.contains("Mo keep calm"),
                "verb agreement: 'Mo keep calm' is grammatically wrong: " + out);
        assertTrue(out.contains("Mo keeps calm"),
                "singular subject must take 'keeps': " + out);
        assertTrue(VeoPromptLinter.isHealthy(out, 2),
                "two-chick prompt must pass the linter: " + VeoPromptLinter.lint(out, 2));
    }

    @Test
    void action_wholeBodyMotionSuppressesSignatureGesture(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = castCompilerFor(tmp);
        // A big tumble already commits the whole body; piling a fine hat-touch on
        // top contradicts the sprawl and morphs limbs (scene-23) — so the per-clip
        // signature gesture is dropped on whole-body / group-motion beats.
        String out = c.compile("All three chicks tumble backwards into the straw, legs in the air",
                "development", List.of("pip", "mo", "bo"), "garden", "midday", "clear",
                "goal", "joy", "natural", null, null, "energetic");
        assertFalse(out.contains("Signature character motion (ONE per clip)"),
                "a whole-body tumble must suppress the fine signature gesture: " + out);
        assertTrue(VeoPromptLinter.isHealthy(out, 3),
                "prompt must pass the linter: " + VeoPromptLinter.lint(out, 3));
    }

    // ---- Cast-scoped Relative size (no absent character may be named) --------

    /** Bible with three chickens that each carry a dna.veoSizeRank, plus a
     *  whole-flock veoScaleRule as the legacy fallback — for the cast-scoped
     *  relative-size checks. */
    private VeoPromptCompiler sizeRankCompilerFor(Path dir) throws Exception {
        Path bible = dir.resolve("channel.yml");
        String chars =
                "characters:\n"
              + "  - id: pip\n    name: \"Pip\"\n    species: chicken\n    dna:\n"
              + "      coreColor: \"cream-white\"\n      veoKey: \"PIP_VEOKEY\"\n"
              + "      veoSizeRank: \"Pip is the smallest\"\n"
              + "  - id: mo\n    name: \"Mo\"\n    species: chicken\n    dna:\n"
              + "      coreColor: \"blue-grey\"\n      veoKey: \"MO_VEOKEY\"\n"
              + "      veoSizeRank: \"Mo is slightly larger\"\n"
              + "  - id: bo\n    name: \"Bo\"\n    species: chicken\n    dna:\n"
              + "      coreColor: \"tan\"\n      veoKey: \"BO_VEOKEY\"\n"
              + "      veoSizeRank: \"Bo is slightly taller and slimmer\"\n";
        String yml = "veoLeanPrompts: true\nveoScaleRule: \"Pip is the smallest, Mo is slightly larger, "
                + "Bo is slightly taller and slimmer. Their proportions never change between or within shots.\"\n"
                + CAMERA + chars;
        Files.writeString(bible, yml);
        OrchestratorProperties props = new OrchestratorProperties(
                new OrchestratorProperties.Services("", "", "", "", "", "", ""),
                new OrchestratorProperties.Poll(10, 5),
                new OrchestratorProperties.Anthropic(null, null, null, "claude-test", null, null),
                new OrchestratorProperties.Defaults("preschoolers", 60, false, "ken_burns"),
                new OrchestratorProperties.Brand("", ""),
                new OrchestratorProperties.Bible(bible.toString()));
        return new VeoPromptCompiler(props);
    }

    @Test
    void relativeSize_soloSceneNamesNoAbsentChicken(@TempDir Path tmp) throws Exception {
        // The original bug: a Pip-only beat still emitted the whole-flock rule,
        // naming Mo and Bo and tempting Veo to render absent chickens.
        VeoPromptCompiler c = sizeRankCompilerFor(tmp);
        String out = c.compile("Close-up of Pip peeking up from the bottom edge", "setup",
                List.of("pip"), "loc", "midday", "clear", "goal", "wonder (5/5)", "natural");
        assertFalse(out.contains("Mo is slightly larger"),
                "a Pip-only scene must not name Mo: " + out);
        assertFalse(out.contains("Bo is slightly taller"),
                "a Pip-only scene must not name Bo: " + out);
        assertFalse(out.contains("Relative size:"),
                "a single-character scene has no relative size to state: " + out);
        assertTrue(VeoPromptLinter.isHealthy(out, 1),
                "solo prompt must still pass the linter: " + VeoPromptLinter.lint(out, 1));
    }

    @Test
    void relativeSize_pairSceneNamesOnlyPresentChickens(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = sizeRankCompilerFor(tmp);
        String out = c.compile("Pip and Mo lean over the egg together", "development",
                List.of("pip", "mo"), "loc", "midday", "clear", "goal", "joy (3/5)", "natural");
        assertTrue(out.contains("Relative size: Pip is the smallest, Mo is slightly larger. "
                        + "Their proportions never change between or within shots."),
                "a Pip+Mo scene must state only Pip and Mo: " + out);
        assertFalse(out.contains("Bo is slightly taller"),
                "a Pip+Mo scene must not name the absent Bo: " + out);
        assertTrue(VeoPromptLinter.isHealthy(out, 2),
                "pair prompt must pass the linter: " + VeoPromptLinter.lint(out, 2));
    }

    @Test
    void relativeSize_fullTrioMatchesCanonicalRuleByteForByte(@TempDir Path tmp) throws Exception {
        // Assembling from per-character ranks must reproduce the legacy whole-flock
        // sentence exactly for the full trio (no golden-output drift).
        VeoPromptCompiler c = sizeRankCompilerFor(tmp);
        String out = c.compile("The whole flock gathers around the egg", "development",
                List.of("pip", "mo", "bo"), "loc", "midday", "clear", "goal", "joy (3/5)", "natural");
        assertTrue(out.contains("Relative size: Pip is the smallest, Mo is slightly larger, "
                        + "Bo is slightly taller and slimmer. Their proportions never change "
                        + "between or within shots."),
                "the full trio must reproduce the canonical relative-size rule: " + out);
        assertTrue(VeoPromptLinter.isHealthy(out, 3),
                "trio prompt must pass the linter: " + VeoPromptLinter.lint(out, 3));
    }

    @Test
    void setting_duskTimeLocksWarmDuskColourMoodNotDaylight(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = castCompilerFor(tmp);
        // A dusk scene must not carry a daytime colour mood (the scene-20 "dusk sky
        // + warm natural daylight" flicker) — the warm-dusk mood is locked.
        String out = c.compile("Pip watches the sky", "resolution", List.of("pip"),
                "garden", "dusk", "clear", "goal", "calm", "natural",
                null, null, "thoughtful");
        assertTrue(out.contains("rich saturated warm dusk light with deep purple-amber tones"),
                "a dusk scene must lock the warm-dusk colour mood: " + out);
        assertFalse(out.toLowerCase().contains("natural daylight"),
                "a dusk scene must not assert daylight: " + out);
        assertTrue(VeoPromptLinter.isHealthy(out, 1),
                "dusk prompt must pass the linter: " + VeoPromptLinter.lint(out, 1));
    }
}
