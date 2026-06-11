-- V6 — content planning fields. plannedPublishAt drives YouTube scheduled
-- publish (private upload + auto-publish at that timestamp). seriesId +
-- episodeNumber group jobs into series so we can plan arcs across episodes.

ALTER TABLE video_jobs ADD COLUMN planned_publish_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE video_jobs ADD COLUMN series_id          VARCHAR(120);
ALTER TABLE video_jobs ADD COLUMN episode_number     INTEGER;

CREATE INDEX idx_video_jobs_planned_publish_at ON video_jobs(planned_publish_at);
CREATE INDEX idx_video_jobs_series_id          ON video_jobs(series_id);
