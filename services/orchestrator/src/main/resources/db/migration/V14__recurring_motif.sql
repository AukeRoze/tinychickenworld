-- V14 — Per-episode recurring visual motif. Decorated eggs are no longer a
-- channel-wide style default; this opt-in field injects a chosen motif (e.g.
-- "decorated pastel eggs", "paper lanterns") into the episode's image prompts.

ALTER TABLE video_jobs ADD COLUMN recurring_motif TEXT;
