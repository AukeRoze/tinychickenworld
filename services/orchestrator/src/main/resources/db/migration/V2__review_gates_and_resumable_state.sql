-- V2 — add review-gate states + persistent fields so the pipeline can pause
-- at a gate and resume later from a fresh process. Status values are stored
-- as TEXT so we just add new enum values transparently — no schema change.

ALTER TABLE video_jobs ADD COLUMN format                 TEXT;
ALTER TABLE video_jobs ADD COLUMN motion_mode            TEXT;
ALTER TABLE video_jobs ADD COLUMN burn_subtitles         BOOLEAN;
ALTER TABLE video_jobs ADD COLUMN privacy_status         TEXT;
ALTER TABLE video_jobs ADD COLUMN background_music_path  TEXT;

-- Working state passed between stages. Stored as TEXT (Jackson-serialised JSON);
-- can be migrated to JSONB later if we need to query inside it.
ALTER TABLE video_jobs ADD COLUMN assembly_scenes        TEXT;

-- Cached metadata so the upload stage can publish without re-running Claude
-- after an UPLOAD_REVIEW_PENDING gate.
ALTER TABLE video_jobs ADD COLUMN metadata_title         TEXT;
ALTER TABLE video_jobs ADD COLUMN metadata_description   TEXT;
ALTER TABLE video_jobs ADD COLUMN metadata_tags          TEXT;
