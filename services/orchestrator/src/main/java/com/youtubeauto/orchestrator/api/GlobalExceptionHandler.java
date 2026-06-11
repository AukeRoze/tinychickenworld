package com.youtubeauto.orchestrator.api;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps service-layer exceptions onto ProblemDetail JSON so the dashboard can
 * show the REAL reason instead of a bare "500 Internal Server Error".
 *
 * Born from a concrete incident (ep-2, scene rerolls): {@code requireClipOk}
 * threw an IllegalStateException with a perfectly actionable message
 * ("Clip voor scène 3 is NIET gegenereerd: FALLBACK — QUOTA …"), but Spring's
 * default error page hides exception messages (server.error.include-message
 * = never), so the operator only saw "500" and had to dig through docker logs.
 *
 * Convention used by the services:
 *  - IllegalArgumentException → 400 (bad input: unknown scene, bad date, …)
 *  - IllegalStateException    → 409 (valid request, wrong state: no master yet,
 *                                    clip generation failed, gate not open, …)
 *  - anything else            → 500, message included, full stack in the log.
 *
 * The frontend (api.js) reads {@code detail} from the ProblemDetail body and
 * shows it in the error toast.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        log.warn("400: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, safe(e));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail notFound(EntityNotFoundException e) {
        log.warn("404: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, safe(e));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException e) {
        log.warn("409: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, safe(e));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("validation failed");
        log.warn("400 (validation): {}", msg);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg);
    }

    /** Browser broke the connection mid-stream (video seek, tab closed, page
     *  reload while the master.mp4 preview loads). Not an error: there is no
     *  client left to answer, and trying to write a ProblemDetail onto the
     *  half-written video/mp4 response only produces a SECOND stacktrace
     *  (HttpMessageNotWritableException). Log one quiet line and stop. */
    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public void clientAbort(org.apache.catalina.connector.ClientAbortException e) {
        log.debug("client aborted the connection mid-response (harmless): {}", safe(e));
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail internal(Exception e) {
        // Client gone mid-stream, wrapped in something else? Same treatment.
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.apache.catalina.connector.ClientAbortException
                    || "Broken pipe".equals(t.getMessage())) {
                log.debug("client aborted the connection mid-response (harmless): {}", safe(e));
                return null;
            }
        }
        // Full stack to the log; message (no stack) to the operator.
        log.error("500 on API call", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                e.getClass().getSimpleName() + ": " + safe(e));
    }

    private static String safe(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
