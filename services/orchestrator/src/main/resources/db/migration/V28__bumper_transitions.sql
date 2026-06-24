-- Intro/outro bumper-overgangen (Auke): per video een instelbare overgang op de
-- grens intro→scène 1 en laatste scène→outro. Type = ffmpeg xfade-naam of "cut";
-- seconds = duur (0,05–1,5). Leeg = de bestaande default-dissolve.
ALTER TABLE video_jobs
    ADD COLUMN IF NOT EXISTS intro_transition_type    varchar(32),
    ADD COLUMN IF NOT EXISTS intro_transition_seconds double precision,
    ADD COLUMN IF NOT EXISTS outro_transition_type    varchar(32),
    ADD COLUMN IF NOT EXISTS outro_transition_seconds double precision;
