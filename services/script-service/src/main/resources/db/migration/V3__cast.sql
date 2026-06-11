-- Cast-aware schema: dialogue lines, characters present per scene, recurring location.
ALTER TABLE script_scenes
    ADD COLUMN lines        JSONB,     -- list of {speaker, text}
    ADD COLUMN characters   JSONB,     -- list of character ids
    ADD COLUMN location_id  TEXT;

-- `narration` becomes a denormalised concatenation of line texts for
-- subtitles/word-count; older rows keep their existing narration value.
ALTER TABLE script_scenes ALTER COLUMN narration DROP NOT NULL;
