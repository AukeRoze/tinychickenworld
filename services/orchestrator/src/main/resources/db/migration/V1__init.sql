CREATE TABLE video_jobs (
    id              UUID PRIMARY KEY,
    topic           TEXT NOT NULL,
    audience        TEXT NOT NULL,
    target_seconds  INT NOT NULL,
    status          TEXT NOT NULL,
    step            TEXT,
    error           TEXT,
    script_job_id   UUID,
    script_id       UUID,
    video_path      TEXT,
    thumbnail_path  TEXT,
    youtube_video_id TEXT,
    youtube_url     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_video_jobs_status ON video_jobs(status);
