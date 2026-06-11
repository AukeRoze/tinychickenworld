CREATE TABLE script_jobs (
    id           UUID PRIMARY KEY,
    topic        TEXT NOT NULL,
    audience     TEXT NOT NULL,
    target_seconds INT NOT NULL,
    status       TEXT NOT NULL,
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE scripts (
    id            UUID PRIMARY KEY,
    job_id        UUID NOT NULL REFERENCES script_jobs(id) ON DELETE CASCADE,
    title         TEXT NOT NULL,
    hook          TEXT NOT NULL,
    cta           TEXT NOT NULL,
    raw_json      JSONB NOT NULL,
    word_count    INT NOT NULL,
    est_seconds   INT NOT NULL,
    model         TEXT NOT NULL,
    prompt_tokens INT,
    completion_tokens INT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE script_scenes (
    id          UUID PRIMARY KEY,
    script_id   UUID NOT NULL REFERENCES scripts(id) ON DELETE CASCADE,
    seq         INT NOT NULL,
    narration   TEXT NOT NULL,
    visual_desc TEXT NOT NULL,
    duration_seconds INT NOT NULL,
    UNIQUE (script_id, seq)
);

CREATE INDEX idx_script_jobs_status ON script_jobs(status);
CREATE INDEX idx_scripts_job_id ON scripts(job_id);
