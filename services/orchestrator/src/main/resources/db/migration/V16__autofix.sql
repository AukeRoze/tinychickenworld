-- V16 — AI-Critic Auto-Fix loop state. When a user triggers Auto-Fix, the job
-- iteratively re-rolls weak scene images and re-assembles until the AI-Critic
-- score reaches `autofix_target` or the hard caps (iterations/rerolls) run out,
-- then pauses for human review. All nullable; null target = loop not active.

ALTER TABLE video_jobs ADD COLUMN autofix_target          INTEGER;
ALTER TABLE video_jobs ADD COLUMN autofix_iterations_left INTEGER;
ALTER TABLE video_jobs ADD COLUMN autofix_rerolls_left    INTEGER;
