package com.youtubeauto.script.api.dto;

import com.youtubeauto.script.domain.JobStatus;

import java.util.List;
import java.util.UUID;

public record ScriptResponse(
        UUID jobId,
        JobStatus status,
        String error,
        ScriptBody script
) {
    public record ScriptBody(
            UUID id,
            String title,
            String hook,
            String cta,
            int wordCount,
            int estSeconds,
            /** Deterministic structure score 0-100 (null on older scripts). */
            Integer structureScore,
            /** Qualitative story-critic score 0-100 (null if critic disabled). */
            Integer criticScore,
            /** Per-axis story-critic scores 0-10 (null if critic disabled) —
             *  consumed by the orchestrator QA Board. */
            Integer comedy,
            Integer emotionalImpact,
            Integer childPsychology,
            /** Story-arc id used for this script (null on older scripts). */
            String storyArc,
            List<Scene> scenes
    ) {}

    public record Scene(
            int seq,
            String narration,
            String visualDesc,
            int durationSeconds,
            List<Line> lines,
            List<String> characters,
            String locationId,
            String phase,
            String timeOfDay,
            String weather,
            String goal,
            String emotion,
            String motionSpeed,
            String endPose,
            String motionDesc
    ) {}

    /** One spoken line within a scene, attributed to a cast character. */
    public record Line(String speaker, String text) {}
}
