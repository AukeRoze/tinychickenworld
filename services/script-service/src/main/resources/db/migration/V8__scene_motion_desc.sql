-- V8 — Dedicated MOTION brief for hero (hook/climax) scenes. visualDesc stays a
-- STILL description (for the image generator); motion_desc describes the start→end
-- movement (camera + character action + ambient) that drives the Veo clip.

ALTER TABLE script_scenes ADD COLUMN motion_desc TEXT;
