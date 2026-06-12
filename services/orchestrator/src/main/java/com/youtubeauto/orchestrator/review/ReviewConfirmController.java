package com.youtubeauto.orchestrator.review;

import com.youtubeauto.orchestrator.service.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/**
 * GET-safe review links (backlog: "GET-mutaties vervangen — link-preview-risico").
 *
 * <p>Mail/notification links point at {@code GET /api/v1/review/confirm?token=...},
 * which only RENDERS a human confirmation page — no mutation happens on GET, so a
 * mail client or link-preview bot that prefetches the link cannot approve a job.
 * The page contains a form that POSTs the signed token to
 * {@code POST /api/v1/review/execute}; only there is the actual
 * {@link PipelineOrchestrator} method invoked.
 *
 * <p>Tokens are HMAC-signed and expiring — see {@link ReviewTokenService}. Use
 * {@link ReviewTokenService#confirmUrl} to build the links.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ReviewConfirmController {

    /** Actions a token may authorize, mapped onto existing orchestrator methods. */
    static final Set<String> SUPPORTED_ACTIONS =
            Set.of("approve", "reject", "retry", "reassemble", "lock-all", "autofix");

    private final ReviewTokenService tokens;
    private final PipelineOrchestrator orchestrator;

    /** Human confirmation page. Renders only — never mutates. */
    @GetMapping(value = "/api/v1/review/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirm(@RequestParam("token") String token) {
        ReviewTokenService.TokenData data;
        try {
            data = tokens.verify(token);
        } catch (ReviewTokenService.InvalidTokenException e) {
            return errorPage(e.getMessage());
        }
        if (!SUPPORTED_ACTIONS.contains(data.action())) {
            return errorPage("unsupported action: " + data.action());
        }
        String html = page("Review-actie bevestigen", """
                <p>Je staat op het punt job <code>%s</code> te <strong>%s</strong>.</p>
                <p class="muted">Link geldig tot %s. Niets gebeurt totdat je hieronder klikt
                &mdash; deze pagina zelf voert g&eacute;&eacute;n actie uit.</p>
                <form method="post" action="/api/v1/review/execute">
                  <input type="hidden" name="token" value="%s">
                  <button type="submit">Ja, %s job %s</button>
                </form>
                """.formatted(
                escape(data.jobId().toString()),
                escape(data.action().toUpperCase()),
                escape(data.expiresAt().toString()),
                escape(token),
                escape(data.action()),
                escape(shortId(data.jobId()))));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    /** Executes the action — POST only, so it requires the human click above. */
    @PostMapping(value = "/api/v1/review/execute",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> execute(@RequestParam("token") String token) {
        ReviewTokenService.TokenData data;
        try {
            data = tokens.verify(token);
        } catch (ReviewTokenService.InvalidTokenException e) {
            return errorPage(e.getMessage());
        }
        UUID id = data.jobId();
        try {
            switch (data.action()) {
                case "approve"    -> orchestrator.approve(id);
                case "reject"     -> orchestrator.reject(id, "rejected via review link");
                case "retry"      -> orchestrator.retry(id);
                case "reassemble" -> orchestrator.reassemble(id);
                case "lock-all"   -> orchestrator.lockAllAndContinue(id);
                case "autofix"    -> orchestrator.startAutoFix(id, null, null);
                default -> {
                    return errorPage("unsupported action: " + data.action());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Review link action {} on {} failed: {}", data.action(), id, e.toString());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.TEXT_HTML)
                    .body(page("Actie mislukt", "<p>Actie <strong>" + escape(data.action())
                            + "</strong> op job <code>" + escape(id.toString())
                            + "</code> is mislukt: " + escape(e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage()) + "</p>"));
        }
        log.info("Review link executed: {} on {}", data.action(), id);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .body(page("Gelukt", "<p>Job <code>" + escape(id.toString())
                        + "</code>: <strong>" + escape(data.action().toUpperCase())
                        + "</strong> uitgevoerd.</p>"
                        + "<p class=\"muted\"><a href=\"/ui/job.html?id=" + escape(id.toString())
                        + "\">Bekijk de job in het dashboard</a></p>"));
    }

    private static ResponseEntity<String> errorPage(String reason) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_HTML)
                .body(page("Ongeldige review-link", "<p>Deze link is ongeldig of verlopen ("
                        + escape(reason) + ").</p><p class=\"muted\">Gebruik het dashboard "
                        + "(<a href=\"/ui/\">/ui/</a>) of vraag een nieuwe mail aan.</p>"));
    }

    /** Minimal inline page — no templates, no static assets needed in a mail context. */
    private static String page(String title, String body) {
        return """
                <!doctype html><html lang="nl"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="robots" content="noindex">
                <title>%s</title>
                <style>
                  body{font-family:system-ui,sans-serif;max-width:34rem;margin:4rem auto;
                       padding:0 1rem;line-height:1.5}
                  code{background:#eee;padding:.1em .3em;border-radius:4px}
                  .muted{color:#666;font-size:.9em}
                  button{font-size:1.05em;padding:.6em 1.4em;border:0;border-radius:6px;
                         background:#1a7f37;color:#fff;cursor:pointer}
                </style></head><body><h1>%s</h1>%s</body></html>
                """.formatted(escape(title), escape(title), body);
    }

    private static String shortId(UUID id) {
        String s = id.toString();
        return s.substring(0, 8);
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
