-- Performance-weighted arc selection: persist which bible story-arc each
-- script was written around, so analytics can score arcs by retention.
ALTER TABLE scripts ADD COLUMN IF NOT EXISTS story_arc VARCHAR(60);
