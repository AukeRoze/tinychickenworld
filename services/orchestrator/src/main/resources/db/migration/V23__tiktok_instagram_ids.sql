-- TikTok/Instagram push status persisted on the job (V11 added Facebook).
-- TikTok's API returns a publish_id, Instagram's Graph API a media-id —
-- neither returns a public URL, so only the ids are stored. The job page
-- uses presence of these ids for honest per-platform status chips.
ALTER TABLE video_jobs
    ADD COLUMN IF NOT EXISTS tiktok_publish_id  varchar(128),
    ADD COLUMN IF NOT EXISTS instagram_media_id varchar(128);
