-- Job-level Veo model choice (UI select): veo3_1_lite / veo3_1_fast / veo3_1.
-- Blank = bible routing per sceneType (legacy behaviour).
ALTER TABLE video_jobs ADD COLUMN IF NOT EXISTS veo_model VARCHAR(60);
