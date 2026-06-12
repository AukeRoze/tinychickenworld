package com.youtubeauto.voice.service;

import com.youtubeauto.voice.bible.BibleLoader;
import com.youtubeauto.voice.config.VoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Mixes a per-location ambient loop UNDER the scene's main audio at low
 * volume. This is what gives a scene that "really being there" feeling —
 * coop has wood creaks, garden has bee buzz, pond has water lap.
 *
 * Skipped silently when the location has no configured ambient or the
 * file doesn't exist. The main audio is always preserved as-is; ambient
 * is purely additive.
 *
 * Weather override: when the scene carries a weather id (eg "lightRain")
 * AND a matching bed exists at {@code {sfxRoot}/ambient/{weather}.mp3},
 * that bed wins over the location bed — so the rain you SEE (the visual
 * weather overlay picked by video-assembly from bible/fx/weather/) also
 * sounds like rain instead of like the garden. "clear" (or any weather
 * without a bed file) falls through to the existing location behaviour.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmbientMixer {

    private final FfmpegRunner ffmpeg;
    private final VoiceProperties props;
    private final BibleLoader bibleLoader;

    @Value("${app.ambient.root:/bible/sfx}")
    private String sfxRoot;
    @Value("${app.ambient.mix-db:-16}")
    private double mixDb;
    @Value("${app.ambient.fade-ms:800}")
    private int fadeMs;

    /**
     * Apply ambient mix to a scene's main audio (location bed only).
     * Kept for source compatibility — delegates to the weather-aware variant.
     */
    public Path mix(Path mainAudio, String locationId, Path target, Path workDir) {
        return mix(mainAudio, locationId, null, target, workDir);
    }

    /**
     * Apply ambient mix to a scene's main audio.
     * @param mainAudio  the existing scene MP3 (sfx-composed or TTS)
     * @param locationId scene location id from the script (eg "coop", "pond")
     * @param weather    scene weather id (eg "lightRain"); when a bed exists at
     *                   {@code {sfxRoot}/ambient/{weather}.mp3} it overrides the
     *                   location bed (matches the visual fx/weather overlay).
     *                   Null, blank or "clear" = no override.
     * @param target     where to write the mixed result
     * @param workDir    temp working directory
     * @return target path (whether it was actually mixed or just copied)
     */
    public Path mix(Path mainAudio, String locationId, String weather, Path target, Path workDir) {
        // 1) Weather bed wins when present — picture (fx/weather overlay) and
        //    sound stay coupled. "clear" never overrides (no clear.mp3 needed).
        Path ambientPath = weatherBed(weather);

        // 2) Otherwise the per-location bed from the bible (loaded at startup).
        if (ambientPath == null) {
            if (locationId == null || locationId.isBlank()) {
                return copy(mainAudio, target);
            }
            Map<String, String> ambientMap = bibleLoader.getAmbientByLocation();
            if (ambientMap == null || ambientMap.isEmpty()) return copy(mainAudio, target);
            String ambientRel = ambientMap.get(locationId);
            if (ambientRel == null || ambientRel.isBlank()) return copy(mainAudio, target);

            ambientPath = Paths.get(sfxRoot, ambientRel);
            if (!Files.exists(ambientPath)) {
                log.debug("ambient file missing: {} (skipping for scene)", ambientPath);
                return copy(mainAudio, target);
            }
        }

        // Mix recipe:
        //   - main audio passes through unchanged
        //   - ambient loops if shorter than main, trims to main duration
        //   - ambient volume reduced to mixDb (~ -16dB default)
        //   - fade in/out for smooth scene boundaries
        // The "amix" filter combines them; weights keep main dominant.
        double fadeS = fadeMs / 1000.0;
        String filter = String.format(java.util.Locale.ROOT,
                "[1:a]aloop=loop=-1:size=2e9,volume=%.2fdB,"
                        + "afade=t=in:st=0:d=%.2f,"
                        + "afade=t=out:st=%.2f:d=%.2f[bg];"
                        + "[0:a][bg]amix=inputs=2:duration=first:dropout_transition=0:weights=1 0.6[aout]",
                mixDb, fadeS,
                Math.max(0.1, probeDuration(mainAudio, workDir) - fadeS), fadeS);

        ffmpeg.run(List.of(
                "-y",
                "-i", mainAudio.toString(),
                "-i", ambientPath.toString(),
                "-filter_complex", filter,
                "-map", "[aout]",
                "-c:a", "libmp3lame", "-q:a", "4",
                target.toString()
        ), workDir);
        log.debug("mixed ambient {} for location={} weather={}",
                ambientPath.getFileName(), locationId, weather);
        return target;
    }

    /** The weather override bed at {@code {sfxRoot}/ambient/{weather}.mp3},
     *  or null when weather is absent, "clear", or the file doesn't exist.
     *  Convention (see bible/sfx/README.md): lightRain.mp3, breezy.mp3,
     *  snow.mp3 — same ids as the visual overlays in bible/fx/weather/. */
    private Path weatherBed(String weather) {
        if (weather == null || weather.isBlank()) return null;
        String w = weather.trim();
        if ("clear".equalsIgnoreCase(w)) return null;
        Path p = Paths.get(sfxRoot, "ambient", w + ".mp3");
        return Files.exists(p) ? p : null;
    }

    private Path copy(Path src, Path target) {
        try {
            if (!src.equals(target)) Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Copy failed: " + e.getMessage(), e);
        }
    }

    private double probeDuration(Path file, Path workDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toString());
            if (workDir != null) pb.directory(workDir.toFile());
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Double.parseDouble(out);
        } catch (Exception e) {
            return 4.0;  // sensible default if probe fails
        }
    }
}
