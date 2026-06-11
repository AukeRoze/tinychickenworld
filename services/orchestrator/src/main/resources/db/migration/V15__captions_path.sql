-- V15 — Path to the generated .srt caption file. Uploaded to YouTube as a
-- real, toggleable caption track (soft subs) instead of burning subtitles into
-- the pixels, so the image stays clean while staying accessible.

ALTER TABLE video_jobs ADD COLUMN captions_path TEXT;
