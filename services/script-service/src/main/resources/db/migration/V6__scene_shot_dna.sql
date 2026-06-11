-- Shot-DNA fields: the directing intent per shot (goal, emotion, pace, end pose)
-- lifted into first-class columns so the prompt compiler can feed VEO a directed
-- shot instead of guessing. Nullable so existing rows keep working.
ALTER TABLE script_scenes ADD COLUMN IF NOT EXISTS goal         text;
ALTER TABLE script_scenes ADD COLUMN IF NOT EXISTS emotion      text;
ALTER TABLE script_scenes ADD COLUMN IF NOT EXISTS motion_speed text;
ALTER TABLE script_scenes ADD COLUMN IF NOT EXISTS end_pose     text;
