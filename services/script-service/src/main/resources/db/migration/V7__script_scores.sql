-- V7 — Pre-render quality scores on a script.
--   structure_score : deterministic beat-sheet score 0-100 (StructureValidator).
--   critic_score    : qualitative story-critic overall 0-100 (ScriptCritic, LLM).
-- Both nullable so older rows and critic-disabled runs stay valid.

ALTER TABLE scripts ADD COLUMN structure_score INTEGER;
ALTER TABLE scripts ADD COLUMN critic_score    INTEGER;
