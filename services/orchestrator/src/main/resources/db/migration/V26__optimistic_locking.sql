-- Optimistic locking for video_jobs (@Version on VideoJob).
-- Async pipeline stages, dashboard controllers (approve/retry/metadata/
-- distribution) and the AnalyticsPoller all do load -> mutate -> save on the
-- same row; the version column turns a lost update into a loud
-- ObjectOptimisticLockingFailureException that the orchestrator's
-- retryOnConflict helper resolves by reloading + reapplying the mutation.
ALTER TABLE video_jobs ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
