// Verwijderd (2026-06-11) — alle review-acties lopen via de front-end (/ui);
// de e-mailnotificaties waren overbodig. De spring-boot-starter-mail
// dependency en de spring.mail-config zijn tegelijk opgeruimd.
// Dit bestand is veilig te deleten.
//
// LET OP bij her-introductie van reviewmails (2026-06-12): bouw actie-links
// NOOIT meer als directe GET-mutaties (/api/v1/videos/{id}/approve etc.) —
// mailclients en link-preview-bots volgen GET-links en keurden zo jobs goed.
// Gebruik ReviewTokenService.confirmUrl(reviewConfig.getReview().mail().baseUrl(),
// jobId, "approve") — dat geeft een HMAC-signed link naar de bevestigingspagina
// GET /api/v1/review/confirm?token=..., waar de mutatie pas na een menselijke
// POST (/api/v1/review/execute) wordt uitgevoerd. Zie ReviewConfirmController.
