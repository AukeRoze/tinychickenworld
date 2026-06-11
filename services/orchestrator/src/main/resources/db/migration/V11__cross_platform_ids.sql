-- Cross-platform distribution IDs alongside YouTube.
-- Used by the auto-cross-poster (Facebook today, TikTok/IG to follow).
ALTER TABLE video_jobs
    ADD COLUMN IF NOT EXISTS facebook_video_id varchar(64),
    ADD COLUMN IF NOT EXISTS facebook_url      text;
