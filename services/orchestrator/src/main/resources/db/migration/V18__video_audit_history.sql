-- V18 — Keep audit HISTORY instead of one row per job. Audits are now inserted
-- on every (re-)audit (incl. each Auto-Fix pass) so the dashboard can show the
-- score progression (78 → 84 → 88). Drop the single-row UNIQUE constraint and
-- index the lookup/ordering columns.

ALTER TABLE video_audit DROP CONSTRAINT IF EXISTS video_audit_video_job_id_key;

CREATE INDEX IF NOT EXISTS idx_video_audit_job_created
    ON video_audit (video_job_id, created_at);
