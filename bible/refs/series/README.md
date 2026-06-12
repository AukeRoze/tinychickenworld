# Series anchors — refs/series/{seriesId}/{characterId}.png

Written automatically by the orchestrator (flag:
`app.series.anchors-enabled`, default true).

* **Promotion** — when a series episode passes every human gate and
  its YouTube upload succeeds, the episode's QC-approved character
  anchors are copied here, overwriting what exists: the NEWEST
  approved episode defines the series canon.
* **Seeding** — the next episode of the same series sends these
  files as extra reference images on its FIRST image batch (and any
  re-roll before its own canon is elected), so episode N+1's cast
  matches episode N — not just the bible. As soon as the new episode
  elects its own episode anchors, those take priority:
  episode canon > series canon > bible refs.
* **New characters** — a character that did not appear in the
  previous episode has no file here and is skipped silently; its
  `bible/refs/{id}.png` anchors remain the baseline.
* **Deliberate look changes** (lifestage: chicks grow up, the
  duckling fledges, a new outfit) — EMPTY this series' folder (and
  update the bible refs); the next approved episode re-establishes
  the canon with the new look. Otherwise seeding would keep pulling
  the cast back to their old appearance.
