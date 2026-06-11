-- Self-learning loop: persist which story arc and thumbnail layout each
-- video used, plus the per-scene retention mapping once analytics land.
ALTER TABLE video_jobs ADD COLUMN IF NOT EXISTS story_arc VARCHAR(60);
ALTER TABLE video_jobs ADD COLUMN IF NOT EXISTS thumbnail_layout VARCHAR(40);
ALTER TABLE video_jobs ADD COLUMN IF NOT EXISTS retention_scenes_json TEXT;
