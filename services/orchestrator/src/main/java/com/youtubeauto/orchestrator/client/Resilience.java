package com.youtubeauto.orchestrator.client;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Shared timeout + retry hardening for the orchestrator's service clients.
 * Before this, several clients called {@code .block()} with no timeout — one
 * hung downstream service meant the job (and its pipeline thread) hung forever.
 *
 * Two profiles, picked per call by COST semantics:
 *
 * <ul>
 *   <li>{@link #paid}: timeout + retry ONLY when the root cause is a
 *       {@link java.net.ConnectException} — i.e. the request provably never
 *       reached the service. Expensive generate calls (Claude / ElevenLabs /
 *       image gen) must never be re-fired on an ambiguous failure: the first
 *       attempt may already be running and billing.</li>
 *   <li>{@link #idempotent}: timeout + retry on connection failures, 5xx and
 *       timeouts — for cheap reads/polls where a duplicate call is free.</li>
 * </ul>
 *
 * Errors that survive the retries propagate exactly as before (this helper
 * never swallows) so existing stage-failure handling is unchanged.
 */
final class Resilience {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(Resilience.class);

    private static final int MAX_RETRIES = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(2);

    private Resilience() {}

    /** Expensive call: timeout + retry only on "request never sent" failures. */
    static <T> T paid(Mono<T> call, Duration timeout, String what) {
        return call.timeout(timeout)
                .retryWhen(Retry.backoff(MAX_RETRIES, FIRST_BACKOFF)
                        .filter(Resilience::connectionRefused)
                        .doBeforeRetry(sig -> log.warn("{} retry #{} after {}",
                                what, sig.totalRetries() + 1, String.valueOf(sig.failure())))
                        .onRetryExhaustedThrow((spec, sig) -> sig.failure()))
                .block();
    }

    /** Cheap idempotent call: timeout + retry on any transient failure. */
    static <T> T idempotent(Mono<T> call, Duration timeout, String what) {
        return call.timeout(timeout)
                .retryWhen(Retry.backoff(MAX_RETRIES, FIRST_BACKOFF)
                        .filter(Resilience::transientFailure)
                        .doBeforeRetry(sig -> log.warn("{} retry #{} after {}",
                                what, sig.totalRetries() + 1, String.valueOf(sig.failure())))
                        .onRetryExhaustedThrow((spec, sig) -> sig.failure()))
                .block();
    }

    /** True only when the request provably never reached the service. */
    private static boolean connectionRefused(Throwable ex) {
        for (Throwable t = ex; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof java.net.ConnectException) return true;
        }
        return false;
    }

    private static boolean transientFailure(Throwable ex) {
        if (connectionRefused(ex)) return true;
        if (ex instanceof WebClientRequestException) return true;
        if (ex instanceof java.util.concurrent.TimeoutException) return true;
        return ex instanceof WebClientResponseException w
                && w.getStatusCode().is5xxServerError();
    }
}
