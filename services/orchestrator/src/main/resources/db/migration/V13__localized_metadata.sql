-- V13 — Per-language localised YouTube metadata (title/description/tags),
-- generated alongside the translated script so each language track can be
-- uploaded with native-language SEO instead of English metadata.

ALTER TABLE video_localization ADD COLUMN localized_title       TEXT;
ALTER TABLE video_localization ADD COLUMN localized_description TEXT;
ALTER TABLE video_localization ADD COLUMN localized_tags        TEXT;  -- comma-separated
