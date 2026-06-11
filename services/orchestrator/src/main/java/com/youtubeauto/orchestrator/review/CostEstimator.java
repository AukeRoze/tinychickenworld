package com.youtubeauto.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.orchestrator.domain.VideoJob;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Estimates the render cost of a job in EUR, surfacing the spend that the
 * pipeline already manages (Veo €/second + the per-video cap) but never showed.
 * Deterministic and dependency-free — uses the job's motion mode, the assembly
 * scenes (hero vs standard) when available, else the target length.
 *
 * Rates mirror the CURRENT bible routing (TEST MODE = everything on Veo 3.1
 * Lite, €0.05/s, 720p). When restoring PROD quality in the bible, also bump
 * these: RATE_HERO→0.40 (veo3_1), RATE_FAST→0.10 (veo3_1_fast). The per-video
 * cap mirrors bible costCapEurPerVideo; the router downgrades to stay under it.
 */
@Component
public class CostEstimator {

    public record Result(double estimateEur, double capEur, List<String> breakdown) {}

    private static final double RATE_HERO = 0.05;   // €/s — TEST: veo3_1_lite (PROD veo3_1: 0.40)
    private static final double RATE_FAST = 0.05;   // €/s — TEST: veo3_1_lite (PROD veo3_1_fast: 0.10)
    private static final double CAP = 45.0;          // per-video cap (mirrors bible)

    private final ObjectMapper mapper = new ObjectMapper();

    public Result estimate(VideoJob job) {
        String mode = job.getMotionMode() == null ? "ken_burns" : job.getMotionMode().toLowerCase();
        List<String> notes = new ArrayList<>();

        if (mode.equals("ken_burns") || mode.equals("song")) {
            notes.add("Motion mode " + mode + " → geen Veo-kosten (FFmpeg/render only)");
            notes.add("Voice (ElevenLabs) ≈ €0,05–0,15 indien aan");
            return new Result(0.0, CAP, notes);
        }

        // Try to read the real scene breakdown (hero = hook/climax).
        int heroSeconds = 0, standardSeconds = 0, scenes = 0;
        try {
            String json = job.getAssemblyScenesJson();
            if (json != null && !json.isBlank()) {
                JsonNode arr = mapper.readTree(json);
                for (JsonNode s : arr) {
                    scenes++;
                    int dur = s.path("durationSeconds").asInt(4);
                    String phase = s.path("phase").asText("").toLowerCase();
                    if (phase.equals("hook") || phase.equals("climax")) heroSeconds += dur;
                    else standardSeconds += dur;
                }
            }
        } catch (Exception ignore) { /* fall back below */ }

        double est;
        if (scenes > 0) {
            if (mode.equals("hybrid")) {
                est = heroSeconds * RATE_HERO;            // only hero clips go to Veo
                notes.add("Hybrid: " + heroSeconds + "s hero × €" + eur(RATE_HERO) + " = €" + eur(est));
                notes.add(standardSeconds + "s standaard via Ken Burns (gratis)");
            } else { // veo (everywhere)
                est = heroSeconds * RATE_HERO + standardSeconds * RATE_FAST;
                notes.add("Veo: " + heroSeconds + "s hero × €" + eur(RATE_HERO) + " + "
                        + standardSeconds + "s × €" + eur(RATE_FAST) + " = €" + eur(est));
            }
        } else {
            // No scene data yet — estimate from target length.
            int total = Math.max(1, job.getTargetSeconds());
            if (mode.equals("hybrid")) {
                int heroGuess = Math.min(total, 42);      // hook + climax of a ~3-min video
                est = heroGuess * RATE_HERO;
                notes.add("Schatting (nog geen scènes): ~" + heroGuess + "s hero × €" + eur(RATE_HERO) + " = €" + eur(est));
            } else {
                est = total * RATE_FAST;
                notes.add("Schatting (nog geen scènes): " + total + "s × €" + eur(RATE_FAST) + " = €" + eur(est));
            }
        }

        boolean capped = est > CAP;
        est = Math.min(est, CAP);
        if (capped) notes.add("Begrensd op de €7-cap (router schaalt terug)");
        return new Result(est, CAP, notes);
    }

    private static String eur(double v) { return String.format(java.util.Locale.US, "%.2f", v); }
}
