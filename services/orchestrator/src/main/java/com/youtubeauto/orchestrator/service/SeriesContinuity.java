package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.client.ScriptServiceClient;
import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Threads "what happened last episode" into the next script's brief so
 * series feel connected. Looks up the most recent completed episode in
 * the same series + reads its closing scene, then formats it as a
 * "previously on..." paragraph that the script-service prepends to the
 * brief.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeriesContinuity {

    private final VideoJobRepository repo;
    private final ScriptServiceClient scriptClient;

    /** Returns a short "previously on..." paragraph or null if no prior
     *  episode in this series exists. */
    public Optional<String> previouslyOn(String seriesId, int currentEpisodeNumber) {
        if (seriesId == null || seriesId.isBlank()) return Optional.empty();
        // Find the most recent COMPLETED episode in the same series with a
        // lower episode number.
        Optional<VideoJob> prior = repo.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .filter(j -> seriesId.equalsIgnoreCase(j.getSeriesId()))
                .filter(j -> j.getStatus() == JobStatus.COMPLETED)
                .filter(j -> j.getEpisodeNumber() != null
                        && j.getEpisodeNumber() < currentEpisodeNumber)
                .max(Comparator.comparingInt(VideoJob::getEpisodeNumber));
        if (prior.isEmpty() || prior.get().getScriptJobId() == null) {
            return Optional.empty();
        }
        try {
            JsonNode script = scriptClient.get(prior.get().getScriptJobId()).path("script");
            String topic = prior.get().getTopic();
            String lesson = prior.get().getLesson();
            // Use the closer phase's narration as the emotional handoff.
            String closing = "";
            for (JsonNode s : script.path("scenes")) {
                if ("closer".equalsIgnoreCase(s.path("phase").asText())) {
                    closing = s.path("narration").asText("");
                    break;
                }
            }
            StringBuilder sb = new StringBuilder("PREVIOUSLY ON TINY CHICKEN WORLD:\n");
            sb.append("Last episode (");
            if (prior.get().getEpisodeNumber() != null) {
                sb.append("Ep ").append(prior.get().getEpisodeNumber()).append(" — ");
            }
            sb.append(topic == null ? "" : topic).append("): ");
            if (lesson != null && !lesson.isBlank()) {
                sb.append("the chicks learned that ").append(lesson.toLowerCase()).append(". ");
            }
            if (!closing.isBlank()) {
                sb.append("It ended on: \"").append(closing.trim()).append("\". ");
            }
            sb.append("\n\nFor THIS episode, find a natural way to acknowledge that ")
              .append("connection — maybe the chicks reference the last adventure in ")
              .append("passing, or build on what they learned. Don't repeat the lesson; ")
              .append("use it as a foundation for something new.");
            return Optional.of(sb.toString());
        } catch (Exception e) {
            log.warn("continuity lookup for series '{}' failed: {}", seriesId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Channel-wide memory for STANDALONE episodes (no seriesId): the last few
     * completed adventures, so the new script (a) never repeats a recent topic
     * or lesson and (b) can drop ONE natural passing reference — the thing
     * that makes a channel feel like a world instead of disconnected videos.
     * Returns empty when there's no history yet.
     */
    public Optional<String> channelMemory(java.util.UUID excludeJobId) {
        try {
            List<VideoJob> recent = repo.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .stream()
                    .filter(j -> j.getStatus() == JobStatus.COMPLETED)
                    .filter(j -> excludeJobId == null || !excludeJobId.equals(j.getId()))
                    .filter(j -> j.getTopic() != null && !j.getTopic().isBlank())
                    .limit(5)
                    .toList();
            if (recent.isEmpty()) return Optional.empty();
            StringBuilder sb = new StringBuilder("CHANNEL MEMORY — the chicks' recent adventures (newest first):\n");
            for (VideoJob j : recent) {
                sb.append("- ").append(j.getTopic());
                if (j.getLesson() != null && !j.getLesson().isBlank()) {
                    sb.append(" (they learned: ").append(j.getLesson()).append(')');
                }
                sb.append('\n');
            }
            sb.append("\nRules for THIS episode: do NOT repeat any of these topics or lessons. ")
              .append("You MAY have one chick make a single, natural passing reference to the ")
              .append("most recent adventure (\"like when we watched the sunrise!\") — one line ")
              .append("max, never an explanation. The episode must stay fully understandable ")
              .append("for a first-time viewer.");
            return Optional.of(sb.toString());
        } catch (Exception e) {
            log.warn("channel memory lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
