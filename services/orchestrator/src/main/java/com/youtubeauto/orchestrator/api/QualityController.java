package com.youtubeauto.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import com.youtubeauto.orchestrator.review.QaBoard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Channel-wide quality overview for the static UI: average QA-board score,
 * per-axis averages (weakest first) and the jobs below the publish threshold.
 */
@RestController
@RequestMapping("/api/v1/quality")
@RequiredArgsConstructor
public class QualityController {

    private final VideoJobRepository jobRepo;
    private final ObjectMapper mapper;

    @GetMapping
    public Map<String, Object> quality() {
        List<VideoJob> scored = jobRepo.findAll().stream()
                .filter(j -> j.getQaBoardScore() != null)
                .toList();

        double avg = scored.stream().mapToInt(VideoJob::getQaBoardScore).average().orElse(0);

        // Per-axis sum + count from each job's stored QA-board JSON.
        Map<String, int[]> agg = new LinkedHashMap<>();
        for (VideoJob j : scored) {
            if (j.getQaBoardJson() == null || j.getQaBoardJson().isBlank()) continue;
            try {
                for (JsonNode ax : mapper.readTree(j.getQaBoardJson()).path("axes")) {
                    String name = ax.path("name").asText("");
                    if (name.isBlank()) continue;
                    int[] v = agg.computeIfAbsent(name, k -> new int[2]);
                    v[0] += ax.path("score").asInt();
                    v[1]++;
                }
            } catch (Exception ignore) { /* skip a malformed board */ }
        }
        List<Map<String, Object>> axes = agg.entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "name", e.getKey(),
                        "avg", e.getValue()[1] == 0 ? 0.0 : (double) e.getValue()[0] / e.getValue()[1]))
                .sorted(Comparator.comparingDouble(a -> (double) a.get("avg")))
                .toList();

        List<Map<String, Object>> below = scored.stream()
                .filter(j -> j.getQaBoardScore() < QaBoard.PUBLISH_MIN)
                .sorted(Comparator.comparingInt(VideoJob::getQaBoardScore))
                .map(j -> Map.<String, Object>of(
                        "id", j.getId().toString(),
                        "topic", j.getTopic() == null ? "" : j.getTopic(),
                        "score", j.getQaBoardScore()))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", scored.size());
        out.put("avg", Math.round(avg * 10) / 10.0);
        out.put("publishMin", QaBoard.PUBLISH_MIN);
        out.put("axes", axes);
        out.put("below", below);
        return out;
    }
}
