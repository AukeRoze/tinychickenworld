-- Song Mode persistence.
-- When motionMode='song', the orchestrator generates lyrics + a Suno track
-- BEFORE assembly, then feeds the song MP3 as the primary audio.
--
-- song_title    : Claude-written title from LyricsGenerator
-- song_style    : Suno style string ("cheerful kids song, ukulele …")
-- song_lyrics   : full lyrics body with [Verse]/[Chorus] markers
-- song_path     : path to song.mp3 (vocal track)
-- karaoke_path  : path to karaoke.mp3 (instrumental, bonus export)
ALTER TABLE video_jobs
    ADD COLUMN IF NOT EXISTS song_title    text,
    ADD COLUMN IF NOT EXISTS song_style    text,
    ADD COLUMN IF NOT EXISTS song_lyrics   text,
    ADD COLUMN IF NOT EXISTS song_path     text,
    ADD COLUMN IF NOT EXISTS karaoke_path  text;
