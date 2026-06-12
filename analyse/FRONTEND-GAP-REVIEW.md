# Front-End ↔ Back-End Architecture Review — "Tiny Chicken World"

*Two-architect review (Senior Front-End Architect + Senior Java Back-End
Architect). Goal: find backend capability that is BUILT but not surfaced in the
server-rendered dashboard. Grounded in the actual controllers, entities and the
dashboard HTML — nothing assumed; the headline items were verified in code.*

---

## 1. Executive Summary

The backend is far richer than the dashboard reveals. The pipeline already ships
**multi-platform distribution (TikTok, Instagram, Facebook), a self-learning
analytics loop, cost calculation, localization, and community-post generation** —
none of which the user can see or trigger from the UI. The dashboard is an
excellent *production cockpit* (create → review → score → publish to YouTube) but
a poor *growth + operations cockpit*: distribution stops at YouTube, the system's
own learnings are invisible, and there is no spend visibility before a paid VEO
run.

The three highest-value gaps, all already coded in the backend:

1. **Multi-platform distribution** (`MultiPlatformController`) — one finished
   video → TikTok + Instagram Reels + Facebook, plus community-post ideas and
   end-screen recipes. The single biggest reach multiplier, invisible today.
2. **The self-learning loop** (`AnalyticsPoller` → `InsightsAggregator` →
   `buildPerformanceHint()`) — the system learns what retains/converts and biases
   new scripts, but never shows the user what it learned or that it's applied.
3. **Cost / budget visibility** (`CostCalculator`, token counts) — computed and
   capped at €7, but never shown. Critical right before the first VEO spend.

> **FE Architect:** "The detail page ends at 'Approve + Upload to YouTube'. As far
> as the user knows, that's where the video's life ends."
> **BE Architect:** "It isn't. `MultiPlatformController` exposes `/distribute/tiktok`,
> `/distribute/instagram`, `/distribute/facebook`, `/distribute/facebook/reel`,
> plus `/distribute/community-posts` and `/distribute/end-screen-recipe`. We even
> persist `facebookVideoId`/`facebookUrl` on `VideoJob`."
> **FE Architect:** "So a 'Distribution' panel with one button per platform is pure
> upside — the backend work is done; we're just not exposing it."

---

## 2. Backend functionality that is NOT used by the front-end

| Capability | Where it lives (backend) | Surfaced in UI? | Value if exposed |
|---|---|---|---|
| TikTok / Instagram / Facebook distribution | `MultiPlatformController` (`/distribute/*`) | ❌ none | 3–4× reach from one rendered file |
| YouTube Community posts (copy-paste ideas) | `CommunityPostService`, `/distribute/community-posts` | ❌ none | Keeps channel "warm" between uploads |
| End-screen card recipe | `/distribute/end-screen-recipe` | ❌ none | Session time / subscriber lift |
| Facebook insights (cross-platform analytics) | `/distribute/facebook/insights/{id}` | ❌ none | Per-platform performance |
| Self-learning performance hint | `buildPerformanceHint()` → script prompt | ❌ invisible | Trust + tuning of the auto-writer |
| Channel insights (top moods/lessons/series) | `InsightsAggregator` | 🟡 partial (Analytics page) | "What's working" decisions |
| Time-series analytics | `VideoAnalytics` snapshots, `AnalyticsPoller` (6h) | 🟡 per-video counts only | Retention/CTR trends over time |
| Cost / budget | `CostCalculator` (Veo €/s), token counts | ❌ none | Spend control before VEO |
| Localization / multi-language metadata | `LocalizationController`, `VideoLocalization` | ❌ none | Reach in NL/DE/FR markets |
| QA Board axis detail (Retention/Sound/Thumbnail notes) | `qaBoardJson.details` | 🟡 bars only | Explains the score |
| Script-critic axis breakdown (arc/comedy/emotional/child-fit) | now in script response | ❌ overall only | Targeted script review |
| Metadata regenerate / edit | `MetadataGenerator`, editable fields | ❌ read-only | Fix a weak title without a full re-run |

---

## 3. Front-End Gaps

**Missing screens / panels**
- **Distribution panel** on the job detail page (per-platform push + status).
- **Cost panel** (per-job estimate + cumulative spend + cap proximity).
- **Insights / "What's working"** panel (the active performance hint, made visible).
- **Localization panel** (language picker + per-language metadata variants).

**Missing actions**
- Push to TikTok / Instagram / Facebook; generate community post; get end-screen recipe.
- Regenerate / inline-edit metadata (title, description, tags).
- Request a localized version; bulk approve/delete; clone a job; save a job as a template.

**Missing search / filters**
- No text search by topic. No filter by series / mood / motion-mode / QA score band.
- No "needs attention" smart filter (failed OR below QA gate OR awaiting review).

**Missing visualisations / alerts**
- No analytics trend charts (views/CTR/retention over time).
- No QA-score-over-time channel trend; no CTR-vs-thumbnail correlation.
- No alert when a scheduled publish is imminent or a job sits in review too long.
- No spend/cap warning banner before a VEO run.

> **FE Architect:** "Users can only see the *current* status and the *latest* audit
> score."
> **BE Architect:** "We keep the full history — `VideoAudit` rows per pass, and
> `VideoAnalytics` snapshots every six hours."
> **FE Architect:** "Then we're missing two timeline components: a quality trail per
> job (we show '78→84→88' but not the channel-wide trend) and a retention/CTR chart
> per video. The data is already there."

---

## 4. New Screens (detail-page panels)

1. **Distribution** — status chips (YouTube ✅ / TikTok / IG / FB), one push-button
   each (disabled + tooltip when the platform's token isn't configured), a
   "Generate community post" box (copy-paste, since YouTube's API is Studio-only),
   and an "End-screen recipe" expander. Backend: `MultiPlatformController`.
2. **Cost** — a small card: estimated render cost (Veo seconds × rate), script
   token cost, and a cumulative channel-spend figure with the €7/video cap shown
   as a progress bar. Backend: `CostCalculator` + script token counts.
3. **Localization** — language multiselect → calls the localize endpoint → shows
   per-language title/description/tags with a copy button. Backend:
   `LocalizationController` / `VideoLocalization`.
4. **QA Board details** (quick win) — make the existing 8 axes expandable to show
   the `source` + the Retention/Sound/Thumbnail `details` we already serialise.

---

## 5. New Dashboards

1. **Growth / Distribution dashboard** — across all videos: which are on which
   platform, which still need cross-posting (a "distribution backlog"), and a
   community-post cadence tracker. *Value:* turns a YouTube-only channel into a
   multi-platform one with the work already built.
   *Data:* `VideoJob` (youtube/facebook IDs), `MultiPlatformController`, `VideoLocalization`.

2. **Performance & Self-Learning dashboard** — time-series of views/CTR/retention
   from `VideoAnalytics`, the top-performing moods/lessons/series from
   `InsightsAggregator`, AND the exact `performanceHint` the system is currently
   feeding new scripts ("biasing toward golden-hour hooks, 8s cold-open"). *Value:*
   makes the auto-writer's tuning legible and trustworthy.
   *Data:* `VideoAnalytics`, `AnalyticsPoller`, `InsightsAggregator`, `buildPerformanceHint()`.

3. **Quality dashboard** — channel-wide QA Board trend (avg /100 over time, and
   per-axis: where are we weakest — Sound? Thumbnail?), plus recurring QC patterns
   (already on the QC-patterns page) tied to the new 8-axis board. *Value:*
   directs where to invest next (e.g. "Sound is consistently our lowest axis").
   *Data:* `qa_board_json`, `VideoAudit`, `QcFinding`.

4. **Cost / budget dashboard** — spend per video and per month, projected cost of
   the queue, cap breaches. *Value:* essential cost control for a paid-VEO pipeline.
   *Data:* `CostCalculator`, token counts, Veo rates.

---

## 6. Quick Wins (sorted by impact ÷ effort)

**< 1 day**
- **QA Board details expander** — data already in `qaBoardJson`; just render it.
- **Cost line on the detail page** — Veo seconds × rate + token counts; one card.
- **Job-list text search + a QA-score column** — client-side filter over the list.
- **Surface the active `performanceHint`** on the create-job form ("new scripts
  are being biased toward: …") — one read, big trust win.
- **Community-post button** — calls `/distribute/community-posts`, shows copy-paste
  text. Zero upload risk (it's idea-generation), pure value.

**< 1 week**
- **Distribution panel** (TikTok / IG / FB push + status) wired to `MultiPlatformController`.
- **Metadata regenerate + inline edit** (title/description/tags).
- **Localization panel** (language picker + per-language metadata).
- **"Needs attention" smart filter + bulk approve/delete** on the job list.
- **Script-critic axis breakdown** on the script review (arc/comedy/emotional/child-fit).

**< 1 sprint**
- **Performance & Self-Learning dashboard** (trend charts + insights + active hint).
- **Growth / Distribution dashboard** (cross-platform coverage + backlog).
- **Quality dashboard** (QA-board trend + per-axis weakness).
- **Clone-job / save-as-template** flow.

---

## 7. Priority Matrix

| Improvement | Business value | Technical complexity | Priority |
|---|---|---|---|
| Distribution panel (TikTok/IG/FB) | Hoog | Middel | **Hoog** |
| Surface performance-hint (self-learning visible) | Hoog | Laag | **Hoog** |
| Cost / budget visibility | Hoog | Laag | **Hoog** |
| Community-post button | Middel | Laag | **Hoog** |
| QA Board details expander | Middel | Laag | **Hoog** |
| Performance & self-learning dashboard | Hoog | Hoog | Middel |
| Localization panel | Middel | Middel | Middel |
| Metadata regenerate/edit | Middel | Laag | Middel |
| Job search + filters + bulk actions | Middel | Laag | Middel |
| Growth/distribution dashboard | Hoog | Hoog | Middel |
| Quality dashboard (QA trend) | Middel | Middel | Middel |
| Clone job / templates | Laag | Laag | Laag |
| Real-time progress (SSE) | Laag | Hoog | Laag |

---

## 8. Concrete recommendations for the next sprint

Theme: **"Make the finished backend visible — distribution, learning, and cost."**

1. **Distribution panel** on the detail page → `MultiPlatformController`
   (`/distribute/tiktok|instagram|facebook|facebook/reel`), with per-platform
   status from `facebookVideoId`/`facebookUrl` and graceful "not configured"
   states. The biggest reach win, and the API already exists.
2. **Cost card + cap banner** → `CostCalculator` + token counts, shown per job and
   as a running channel spend with the €7 cap. Ship before the first VEO run.
3. **Self-learning made visible** → render the active `performanceHint` on the
   create form and a small "what's working" strip from `InsightsAggregator`.
4. **Two same-day quick wins** → QA Board details expander + community-post button.
5. **Stretch** → start the Performance & Self-Learning dashboard (time-series from
   `VideoAnalytics`), since `AnalyticsPoller` is already filling the table every 6h.

> **BE Architect:** "Almost none of this is new backend work — it's wiring existing
> endpoints and persisted data into the dashboard."
> **FE Architect:** "Exactly. The cheapest roadmap we have is the one where the
> backend already did the hard part."
