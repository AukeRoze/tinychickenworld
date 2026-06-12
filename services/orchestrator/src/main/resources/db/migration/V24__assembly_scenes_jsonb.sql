-- V24 — assembly_scenes TEXT → JSONB (the "can be migrated to JSONB later"
-- promised in V2). The column has only ever been written with Jackson-
-- serialised JSON, but old/aborted rows may contain an empty string, and we
-- refuse to let one historic bad row brick the migration:
--
--  1) a DO block NULLs any row whose content does not cast to jsonb
--     (covers '' as well — ''::jsonb throws — and any truncated JSON);
--  2) the ALTER itself still carries a defensive NULLIF(btrim(...),'')
--     so the cast in step 2 can never see an empty string.
--
-- Java side stays String (VideoJob.assemblyScenesJson) with
-- @JdbcTypeCode(SqlTypes.JSON) — the same proven pattern as
-- script-service's Script.rawJson / ScriptScene.linesJson.

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT id, assembly_scenes FROM video_jobs
             WHERE assembly_scenes IS NOT NULL LOOP
        BEGIN
            PERFORM r.assembly_scenes::jsonb;
        EXCEPTION WHEN OTHERS THEN
            UPDATE video_jobs SET assembly_scenes = NULL WHERE id = r.id;
        END;
    END LOOP;
END $$;

ALTER TABLE video_jobs
    ALTER COLUMN assembly_scenes TYPE jsonb
    USING NULLIF(btrim(assembly_scenes), '')::jsonb;
