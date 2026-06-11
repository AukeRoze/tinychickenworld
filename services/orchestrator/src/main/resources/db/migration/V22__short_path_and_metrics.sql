-- Shorts-extractie + productie-metrics (audit 2026-06-11).
-- short_path: pad naar de automatisch afgeleide verticale Short (out/short.mp4).
-- metrics_json: per-job productiecijfers (Veo-kosten, stretch-factor, duur)
--               zodat kosten en render-groei zichtbaar worden op de jobpagina.
-- (Eerst abusievelijk als V3 aangemaakt — botste met V3__creative_brief_fields.)
ALTER TABLE video_jobs ADD COLUMN IF NOT EXISTS short_path  text;
ALTER TABLE video_jobs ADD COLUMN IF NOT EXISTS metrics_json text;
