-- V4 — per-scene image review + image reuse from prior job.
--   reuse_images_from_job: if set, runAssetsStage copies images from that
--     job's workdir instead of calling image-service. Voice still runs.
--   locked_scene_seqs:    comma-separated list of scene seq numbers that
--     the reviewer has approved. When all scenes are present, pipeline
--     advances from IMAGES_REVIEW_PENDING to the next stage.
ALTER TABLE video_jobs ADD COLUMN reuse_images_from_job UUID;
ALTER TABLE video_jobs ADD COLUMN locked_scene_seqs    TEXT;
