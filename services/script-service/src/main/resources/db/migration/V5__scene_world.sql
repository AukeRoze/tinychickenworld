-- World fields lifted from free-text visualDesc into first-class columns so the
-- whole pipeline (image-gen, Veo prompt compiler) can use them reliably instead
-- of guessing. Nullable so existing rows keep working.
ALTER TABLE script_scenes ADD COLUMN IF NOT EXISTS time_of_day text;
ALTER TABLE script_scenes ADD COLUMN IF NOT EXISTS weather     text;
