-- AI Quality Reviewer findings, one row per audited master.
--
-- score          : 0-100 overall (Claude-judged)
-- character_drift: 0-100, 100=perfect cast consistency across frames
-- audio_balance  : 0-100, 100=clean voice over music
-- framing        : 0-100, 100=composition + safe zones
-- branding       : 0-100, 100=palette + logo + intro/outro consistency
-- findings       : JSON list of { severity: critical|major|minor, area, message }
-- frames_inspected: count of keyframes analysed
CREATE TABLE IF NOT EXISTS video_audit (
    id              uuid PRIMARY KEY,
    video_job_id    uuid NOT NULL REFERENCES video_jobs(id) ON DELETE CASCADE,
    score           int  NOT NULL,
    character_drift int,
    audio_balance   int,
    framing         int,
    branding        int,
    findings        text,
    frames_inspected int,
    model           varchar(64),
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (video_job_id)
);
