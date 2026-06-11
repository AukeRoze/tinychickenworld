package com.youtubeauto.orchestrator.review;

import com.youtubeauto.orchestrator.domain.VideoJob;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Role 9 — Sound Design Director, as a DETERMINISTIC scorer (no LLM).
 *
 * At Pixar, sound carries ~half the emotion. We can't "listen" here, but we can
 * verify the sound DESIGN is in place and balanced from signals we already have:
 * the vision audit's voice-vs-music balance, plus the presence of a music bed,
 * an ambient-sound library, a foley/SFX library and the brand sting. A video
 * with clean voice-over but no music, ambience or foley is flat — this surfaces
 * that before upload.
 */
@Component
public class SoundScorer {

    public record Result(int score, List<String> notes) {}

    /** @param audioBalance the vision audit's audio_balance (0-100, voice above
     *                      music). Null when no audit ran → treated as neutral. */
    public Result evaluate(VideoJob job, Integer audioBalance) {
        List<String> notes = new ArrayList<>();

        // Voice-vs-music balance from the vision audit. (weight 40) ---
        int balancePts;
        if (audioBalance == null) {
            balancePts = 24; // neutral 60% when unknown — don't over-reward or punish
            notes.add("Balance unknown (no audit) → " + balancePts + "/40");
        } else {
            balancePts = Math.round(40f * audioBalance / 100f);
            notes.add("Voice/music balance " + audioBalance + "/100 → " + balancePts + "/40");
        }

        // Music bed selected for this job. (weight 20) ---
        boolean music = job.getBackgroundMusicPath() != null && !job.getBackgroundMusicPath().isBlank();
        int musicPts = music ? 20 : 0;
        notes.add("Music bed " + (music ? "present" : "MISSING") + " → " + musicPts + "/20");

        // Ambient-sound library (per-location beds). (weight 15) ---
        boolean ambient = hasAudio(Paths.get("/bible/sfx/ambient"));
        int ambientPts = ambient ? 15 : 0;
        notes.add("Ambient library " + (ambient ? "present" : "empty") + " → " + ambientPts + "/15");

        // Foley / character SFX library. (weight 15) ---
        boolean foley = hasAudio(Paths.get("/bible/sfx"));
        int foleyPts = foley ? 15 : 0;
        notes.add("Foley/SFX library " + (foley ? "present" : "empty") + " → " + foleyPts + "/15");

        // Brand audio sting. (weight 10) ---
        boolean sting = Files.exists(Paths.get("/bible/sting.mp3"));
        int stingPts = sting ? 10 : 0;
        notes.add("Brand sting " + (sting ? "present" : "MISSING") + " → " + stingPts + "/10");

        int score = Math.max(0, Math.min(100, balancePts + musicPts + ambientPts + foleyPts + stingPts));
        return new Result(score, notes);
    }

    /** True when the directory exists and contains at least one audio file
     *  (so an emptied placeholder folder doesn't score as "present"). */
    private boolean hasAudio(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".m4a");
            });
        } catch (IOException e) {
            return false;
        }
    }
}
