-- V7 — YouTube analytics snapshots. One row per video × poll. Most recent
-- row for a given video_job_id = current state. Older rows = history.

CREATE TABLE video_analytics (
    id                          UUID PRIMARY KEY,
    video_job_id                UUID NOT NULL REFERENCES video_jobs(id) ON DELETE CASCADE,
    youtube_video_id            VARCHAR(50) NOT NULL,
    fetched_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    views                       BIGINT,
    likes                       INTEGER,
    comments                    INTEGER,
    favorites                   INTEGER,
    -- These come from the Analytics API (require additional scope) and may
    -- be null for the first poll or for very new videos.
    watch_time_minutes          BIGINT,
    average_view_duration_sec   INTEGER,
    average_view_percentage     REAL,
    impressions                 BIGINT,
    click_through_rate          REAL,
    subscriber_gain             INTEGER,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_video_analytics_job_id      ON video_analytics(video_job_id);
CREATE INDEX idx_video_analytics_youtube     ON video_analytics(youtube_video_id);
CREATE INDEX idx_video_analytics_fetched_at  ON video_analytics(fetched_at DESC);
