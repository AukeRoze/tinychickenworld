-- Instagram permalink persisted on the job (V23 added the media-id only).
-- After a successful Reels publish the upload-service now does a best-effort
-- GET /{mediaId}?fields=permalink lookup; the orchestrator's distribution
-- proxy stores the result here so the job page can LINK the Instagram chip.
-- NULL = lookup failed or never pushed (the media-id remains the honest
-- "was it pushed" signal).
ALTER TABLE video_jobs
    ADD COLUMN IF NOT EXISTS instagram_url varchar(512);
