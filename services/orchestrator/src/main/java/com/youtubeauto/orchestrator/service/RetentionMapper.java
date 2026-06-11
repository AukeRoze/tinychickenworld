package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a YouTube audience-retention curve (fractions of the video → watch
 * ratio) onto the episode's SCENE timeline, so a drop-off stops being "at
 * 1:47" and becomes "scene 14, development, the second pond beat". That is
 * the difference between a dashboard and an editorial note.
 *
 * The master video = branded intro + scenes + outro. The scene window is
 * therefore shifted by an estimated intro offset (master duration minus the
 * scripted scene total, biased 70/30 toward the intro — the intro is the
 * longer brand element). Approximation is fine: scenes are 4-8s, the offset
 * error is ~1-2s, and we aggregate per scene anyway.
 */
@Slf4j
public final class RetentionMapper {

    private static final double INTRO_SHARE_OF_BRAND = 0.7;

    private RetentionMapper() {}

    /**
     * @param retention   Analytics API response: columnHeaders + rows of
     *                    (elapsedVideoTimeRatio, audienceWatchRatio, …)
     * @param scenesJson  the job's assemblyScenesJson (seq/durationSeconds/phase)
     * @param isoDuration master duration from the Data API, e.g. "PT2M19S"
     * @return JSON array [{seq,phase,startSec,endSec,avgWatchRatio,drop}] or
     *         null when there's not enough data to map.
     */
    public static String mapToScenes(JsonNode retention, String scenesJson, String isoDuration) {
        try {
            if (retention == null || retention.path("rows").isEmpty()) return null;
            if (scenesJson == null || scenesJson.isBlank()) return null;
            double videoDur = parseIsoSeconds(isoDuration);
            if (videoDur <= 0) return null;

            ObjectMapper m = new ObjectMapper();
            JsonNode scenes = m.readTree(scenesJson);
            if (!scenes.isArray() || scenes.isEmpty()) return null;

            // Curve: ratio (0..1) -> audienceWatchRatio. Column order per API:
            // elapsedVideoTimeRatio first; find audienceWatchRatio by header.
            int ratioIdx = headerIndex(retention, "elapsedVideoTimeRatio", 0);
            int watchIdx = headerIndex(retention, "audienceWatchRatio", 1);
            List<double[]> curve = new ArrayList<>();
            for (JsonNode row : retention.path("rows")) {
                curve.add(new double[]{ row.path(ratioIdx).asDouble(), row.path(watchIdx).asDouble() });
            }
            if (curve.size() < 5) return null;

            double sceneTotal = 0;
            for (JsonNode s : scenes) sceneTotal += s.path("durationSeconds").asDouble(0);
            double brand = Math.max(0, videoDur - sceneTotal);
            double offset = brand * INTRO_SHARE_OF_BRAND;

            ArrayNode out = m.createArrayNode();
            double cursor = offset;
            for (JsonNode s : scenes) {
                double dur = s.path("durationSeconds").asDouble(0);
                double start = cursor, end = cursor + dur;
                cursor = end;
                double rStart = start / videoDur, rEnd = end / videoDur;
                double sum = 0, first = -1, last = -1;
                int n = 0;
                for (double[] p : curve) {
                    if (p[0] < rStart || p[0] > rEnd) continue;
                    sum += p[1];
                    if (first < 0) first = p[1];
                    last = p[1];
                    n++;
                }
                if (n == 0) continue;
                ObjectNode o = out.addObject();
                o.put("seq", s.path("seq").asInt());
                o.put("phase", s.path("phase").asText(""));
                o.put("startSec", Math.round(start * 10) / 10.0);
                o.put("endSec", Math.round(end * 10) / 10.0);
                o.put("avgWatchRatio", Math.round(sum / n * 1000) / 1000.0);
                o.put("drop", Math.round((first - last) * 1000) / 1000.0);
            }
            if (out.isEmpty()) return null;

            // Editorial log: the three biggest in-scene drops, with phase.
            List<JsonNode> worst = new ArrayList<>();
            out.forEach(worst::add);
            worst.sort((a, b) -> Double.compare(b.path("drop").asDouble(), a.path("drop").asDouble()));
            StringBuilder note = new StringBuilder("Retention drops: ");
            for (int i = 0; i < Math.min(3, worst.size()); i++) {
                JsonNode w = worst.get(i);
                if (w.path("drop").asDouble() <= 0) break;
                note.append(String.format("scene %d (%s) -%.1f%%; ",
                        w.path("seq").asInt(), w.path("phase").asText("?"),
                        w.path("drop").asDouble() * 100));
            }
            log.info(note.toString());
            return m.writeValueAsString(out);
        } catch (Exception e) {
            log.debug("retention mapping failed: {}", e.getMessage());
            return null;
        }
    }

    private static int headerIndex(JsonNode resp, String name, int fallback) {
        JsonNode headers = resp.path("columnHeaders");
        for (int i = 0; i < headers.size(); i++) {
            if (name.equals(headers.path(i).path("name").asText())) return i;
        }
        return fallback;
    }

    /** "PT2M19S" → 139.0; returns -1 on parse failure. */
    static double parseIsoSeconds(String iso) {
        try {
            return Duration.parse(iso).toMillis() / 1000.0;
        } catch (Exception e) {
            return -1;
        }
    }
}
