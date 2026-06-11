-- V12 — Pre-render quality scores copied from the script stage onto the job,
-- so the Polish/quality scorer can weigh story structure + critic verdict
-- before upload. Both nullable (populated when the script stage completes).

ALTER TABLE video_jobs ADD COLUMN structure_score INTEGER;
ALTER TABLE video_jobs ADD COLUMN critic_score    INTEGER;
