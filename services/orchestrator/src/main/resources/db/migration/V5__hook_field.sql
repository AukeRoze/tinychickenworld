-- V5 — explicit HOOK seed for the enforced episode structure (first 0-8s).
ALTER TABLE video_jobs ADD COLUMN hook TEXT;
