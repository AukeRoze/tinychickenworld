package com.youtubeauto.orchestrator.review;

// Retired. The classic server-rendered dashboard (/dashboard/*) was replaced by
// the static UI under /ui. The endpoints the new UI still needs were moved:
//   - scene images, thumbnails, master video, thumbnail-select → MediaController
//   - analytics poll                                           → AnalyticsController
//   - sections (calendar/backlog/quality/distribution/qc/analytics) → /ui pages
//     backed by VideoController, QualityController, OverviewController,
//     AnalyticsController, BrandController.
// This file is intentionally empty so the classic UI no longer registers routes.
