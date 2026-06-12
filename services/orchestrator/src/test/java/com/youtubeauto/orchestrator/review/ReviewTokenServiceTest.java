package com.youtubeauto.orchestrator.review;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the signed review-link tokens (link-preview fix): a token must round-trip,
 * and forged / expired / wrong-action tokens must all be rejected. Plain JUnit 5,
 * no Spring context — the service has an explicit (secret, ttl, clock) constructor.
 */
class ReviewTokenServiceTest {

    private static final String SECRET = "test-secret-please-rotate";
    private static final Instant NOW = Instant.parse("2026-06-12T10:00:00Z");

    private static ReviewTokenService service(Instant now) {
        return new ReviewTokenService(SECRET, 7, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void tokenRoundTrips() {
        ReviewTokenService svc = service(NOW);
        UUID jobId = UUID.randomUUID();

        String token = svc.createToken(jobId, "approve");
        ReviewTokenService.TokenData data = svc.verify(token, "approve");

        assertEquals(jobId, data.jobId());
        assertEquals("approve", data.action());
        assertEquals(NOW.plus(Duration.ofDays(7)).getEpochSecond(),
                data.expiresAt().getEpochSecond(), "expiry must be now + ttl");
    }

    @Test
    void expiredTokenIsRejected() {
        String token = service(NOW).createToken(UUID.randomUUID(), "retry");

        // Same secret, clock advanced past the 7-day TTL.
        ReviewTokenService later = service(NOW.plus(Duration.ofDays(8)));
        var e = assertThrows(ReviewTokenService.InvalidTokenException.class,
                () -> later.verify(token));
        assertTrue(e.getMessage().contains("expired"));

        // Just inside the TTL still works.
        assertDoesNotThrow(() -> service(NOW.plus(Duration.ofDays(6))).verify(token));
    }

    @Test
    void forgedTokenIsRejected() {
        ReviewTokenService svc = service(NOW);
        UUID jobId = UUID.randomUUID();
        String token = svc.createToken(jobId, "reject");

        // Tamper: swap the action inside the payload but keep the original signature.
        String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        String[] parts = raw.split("\\|", -1);
        assertEquals(5, parts.length, "token layout: jobId|action|expiry|nonce|sig");
        parts[1] = "approve";
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join("|", parts).getBytes(StandardCharsets.UTF_8));

        assertThrows(ReviewTokenService.InvalidTokenException.class, () -> svc.verify(forged));

        // A token signed with a DIFFERENT secret is rejected too.
        ReviewTokenService otherSecret =
                new ReviewTokenService("another-secret", 7, Clock.fixed(NOW, ZoneOffset.UTC));
        String foreign = otherSecret.createToken(jobId, "reject");
        assertThrows(ReviewTokenService.InvalidTokenException.class, () -> svc.verify(foreign));

        // Garbage input never passes.
        assertThrows(ReviewTokenService.InvalidTokenException.class, () -> svc.verify("not-a-token"));
        assertThrows(ReviewTokenService.InvalidTokenException.class, () -> svc.verify(""));
    }

    @Test
    void wrongActionIsRejected() {
        ReviewTokenService svc = service(NOW);
        String token = svc.createToken(UUID.randomUUID(), "retry");

        // Signature and expiry are fine — but the token authorizes retry, not approve.
        assertDoesNotThrow(() -> svc.verify(token));
        assertThrows(ReviewTokenService.InvalidTokenException.class,
                () -> svc.verify(token, "approve"));
    }

    @Test
    void confirmUrlPointsAtConfirmEndpoint() {
        ReviewTokenService svc = service(NOW);
        UUID jobId = UUID.randomUUID();

        String url = svc.confirmUrl("http://localhost:8080/", jobId, "approve");
        assertTrue(url.startsWith("http://localhost:8080/api/v1/review/confirm?token="),
                "trailing slash on baseUrl must not double up: " + url);

        String token = url.substring(url.indexOf("token=") + "token=".length());
        assertEquals(jobId, svc.verify(token, "approve").jobId());
    }
}
