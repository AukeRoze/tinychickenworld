package com.youtubeauto.orchestrator.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * HMAC-SHA256-signed, expiring action tokens for review links (mail /
 * notifications). Replaces the old direct GET-mutation links
 * ({@code GET /api/v1/videos/{id}/approve} etc.) that a mail client or
 * link-preview bot could "click" and thereby approve a job unintentionally.
 *
 * <p>Token layout: {@code base64url(jobId|action|expiryEpochSec|nonce|base64url(hmac))}
 * where the HMAC covers {@code jobId|action|expiryEpochSec|nonce}. Verification
 * compares signatures constant-time via {@link MessageDigest#isEqual}.
 *
 * <p>The secret comes from {@code app.review.token-secret} (env
 * {@code REVIEW_TOKEN_SECRET}). When unset, a random secret is generated at
 * startup — links then stop working after a restart (logged as a warning).
 * TTL: {@code app.review.token-ttl-days}, default 7 days.
 *
 * <p>Tokens are stateless: the nonce makes each token unique but there is no
 * replay store, so a token stays valid until expiry (acceptable here because
 * the actions are idempotent-ish review transitions guarded by job status).
 */
@Slf4j
@Component
public class ReviewTokenService {

    /** Verified token contents. */
    public record TokenData(UUID jobId, String action, Instant expiresAt) {}

    /** Thrown when a token is malformed, forged, expired or for another action. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) { super(message); }
    }

    private static final long DEFAULT_TTL_DAYS = 7;
    private static final Pattern ACTION_PATTERN = Pattern.compile("[a-z][a-z0-9-]*");

    private final byte[] secret;
    private final long ttlDays;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ReviewTokenService(@Value("${app.review.token-secret:}") String secret,
                              @Value("${app.review.token-ttl-days:7}") long ttlDays) {
        this(secret, ttlDays, Clock.systemUTC());
    }

    /** Test-friendly constructor: explicit secret, TTL and clock. */
    public ReviewTokenService(String secret, long ttlDays, Clock clock) {
        this.ttlDays = ttlDays > 0 ? ttlDays : DEFAULT_TTL_DAYS;
        this.clock = clock;
        if (secret == null || secret.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.secret = generated;
            log.warn("app.review.token-secret (REVIEW_TOKEN_SECRET) is not set — generated a "
                    + "random secret at startup. Review links in already-sent mails become "
                    + "INVALID after every orchestrator restart. Set REVIEW_TOKEN_SECRET to "
                    + "keep links stable.");
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Create a signed token authorizing {@code action} on {@code jobId} until TTL expiry. */
    public String createToken(UUID jobId, String action) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        if (action == null || !ACTION_PATTERN.matcher(action).matches()) {
            throw new IllegalArgumentException("invalid action: " + action);
        }
        long expiry = clock.instant().plus(Duration.ofDays(ttlDays)).getEpochSecond();
        byte[] nonceBytes = new byte[8];
        random.nextBytes(nonceBytes);
        String nonce = b64(nonceBytes);
        String payload = jobId + "|" + action + "|" + expiry + "|" + nonce;
        String sig = b64(hmac(payload));
        return b64((payload + "|" + sig).getBytes(StandardCharsets.UTF_8));
    }

    /** Verify signature + expiry; returns the token's contents or throws. */
    public TokenData verify(String token) {
        if (token == null || token.isBlank()) throw new InvalidTokenException("missing token");
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("malformed token");
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 5) throw new InvalidTokenException("malformed token");

        String payload = parts[0] + "|" + parts[1] + "|" + parts[2] + "|" + parts[3];
        byte[] expected = hmac(payload);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[4]);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("malformed token");
        }
        // Constant-time compare — never short-circuit on the first differing byte.
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new InvalidTokenException("invalid signature");
        }

        long expiry;
        UUID jobId;
        try {
            expiry = Long.parseLong(parts[2]);
            jobId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("malformed token");
        }
        if (clock.instant().getEpochSecond() > expiry) {
            throw new InvalidTokenException("token expired");
        }
        return new TokenData(jobId, parts[1], Instant.ofEpochSecond(expiry));
    }

    /** Verify and additionally require the token to carry {@code expectedAction}. */
    public TokenData verify(String token, String expectedAction) {
        TokenData data = verify(token);
        if (!data.action().equals(expectedAction)) {
            throw new InvalidTokenException("token is for action '" + data.action()
                    + "', not '" + expectedAction + "'");
        }
        return data;
    }

    /**
     * Build a GET-safe review link for mails/notifications:
     * {@code {baseUrl}/api/v1/review/confirm?token=...}. The confirm page only
     * SHOWS what would happen; the mutation requires a human POST on that page.
     */
    public String confirmUrl(String baseUrl, UUID jobId, String action) {
        String base = baseUrl == null ? "" : baseUrl;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/api/v1/review/confirm?token=" + createToken(jobId, action);
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is mandatory in every JRE — this cannot happen in practice.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
