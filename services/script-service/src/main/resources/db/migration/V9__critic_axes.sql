-- Per-axis story-critic scores (0-10), surfaced so the orchestrator QA Board can
-- map Humor / Emotional Impact / child-psychology directly. Nullable: older
-- scripts and critic-disabled runs simply have no per-axis breakdown.
ALTER TABLE scripts ADD COLUMN critic_comedy     INTEGER;
ALTER TABLE scripts ADD COLUMN critic_emotional  INTEGER;
ALTER TABLE scripts ADD COLUMN critic_psychology INTEGER;
