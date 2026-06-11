-- Episode-structure phase per scene (hook, setup, development, climax,
-- resolution, closer). Nullable so older rows remain valid. Drives
-- quality review + Veo scene-type routing downstream.
ALTER TABLE script_scenes
    ADD COLUMN phase VARCHAR(32);
