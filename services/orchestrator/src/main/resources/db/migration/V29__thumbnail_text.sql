-- Per-episode custom thumbnail overlay text. When set, it is drawn verbatim on
-- every thumbnail variant (overriding the auto-derived title headline).
ALTER TABLE video_jobs ADD COLUMN thumbnail_text text;
