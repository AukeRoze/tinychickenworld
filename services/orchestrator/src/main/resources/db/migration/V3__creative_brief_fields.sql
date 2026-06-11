-- V3 — accept richer creative brief on the request side.
ALTER TABLE video_jobs ADD COLUMN brief  TEXT;
ALTER TABLE video_jobs ADD COLUMN lesson TEXT;
ALTER TABLE video_jobs ADD COLUMN mood   TEXT;
ALTER TABLE video_jobs ADD COLUMN angle  TEXT;
