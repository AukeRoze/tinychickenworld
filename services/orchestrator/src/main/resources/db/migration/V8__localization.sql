-- V8 — Per-video, per-language localised assets. One job can spawn
-- multiple language tracks. Each row points to translated script JSON +
-- per-language audio + YouTube video id if uploaded.

CREATE TABLE video_localization (
    id                    UUID PRIMARY KEY,
    video_job_id          UUID NOT NULL REFERENCES video_jobs(id) ON DELETE CASCADE,
    language_code         VARCHAR(8) NOT NULL,
    translated_script     TEXT,                          -- JSON of translated script
    audio_dir             VARCHAR(500),                   -- per-language scene audio
    subtitle_path         VARCHAR(500),                   -- per-language SRT
    youtube_video_id      VARCHAR(50),                    -- if uploaded to that lang channel
    youtube_url           VARCHAR(500),
    status                VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    error                 TEXT,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(video_job_id, language_code)
);

CREATE INDEX idx_video_localization_job_id    ON video_localization(video_job_id);
CREATE INDEX idx_video_localization_lang      ON video_localization(language_code);
