-- V17 — Recorded vision-QC failures, so recurring problems surface as patterns.
-- If the same category/character keeps failing across videos, that's a signal to
-- harden the prompt/anchor permanently instead of re-fixing it every render.

CREATE TABLE qc_finding (
    id              UUID PRIMARY KEY,
    video_job_id    UUID NOT NULL REFERENCES video_jobs(id) ON DELETE CASCADE,
    seq             INTEGER,
    category        VARCHAR(40) NOT NULL,
    character_hint  VARCHAR(20),
    issue           TEXT,
    source          VARCHAR(20),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_qc_finding_created  ON qc_finding(created_at);
CREATE INDEX idx_qc_finding_category ON qc_finding(category);
CREATE INDEX idx_qc_finding_job      ON qc_finding(video_job_id);
