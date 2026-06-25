package com.youtubeauto.orchestrator.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Central kill-switch gate for paid Anthropic calls in the orchestrator.
 *
 * <p>Mirrors the WebClient-level {@code disabledFilter} in {@link WebClientConfig},
 * but lets every call-site check the flag UP FRONT and skip cleanly — one INFO
 * line plus a graceful degraded return — instead of building a request, firing
 * it, and catching the filter's exception deep inside Reactor. That mid-flight
 * failure dumped a full {@code FluxOnAssembly} stack trace into the log for
 * every optional feature (QC, scorers, metadata, …) on every job while
 * {@code ANTHROPIC_ENABLED=false}.
 *
 * <p>The WebClient filter stays as the hard backstop: a call-site that forgets
 * to consult this gate still cannot spend money while the kill-switch is on — it
 * just fails loudly instead of skipping quietly. So this gate is about log
 * hygiene and graceful degradation, not about safety.
 *
 * <p>Reads the same property as everything else ({@code app.anthropic.enabled},
 * bound from the {@code ANTHROPIC_ENABLED} env var). Constructor-injected so it
 * unit-tests without Spring: {@code new AnthropicGate(false)}.
 */
@Slf4j
@Component
public class AnthropicGate {

    private final boolean enabled;

    public AnthropicGate(@Value("${app.anthropic.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /** True when paid Anthropic calls are allowed (kill-switch off). */
    public boolean enabled() {
        return enabled;
    }

    /**
     * True when the kill-switch is ON, in which case the caller should skip its
     * paid Anthropic call and return a graceful fallback. Logs a single clean
     * line naming the feature — so the log still shows WHY a step was skipped,
     * without the Reactor stack trace the WebClient filter would produce.
     *
     * @param feature human-readable name of the step being skipped, e.g. "Clip QC"
     */
    public boolean skip(String feature) {
        if (!enabled) {
            log.info("{} skipped — ANTHROPIC_ENABLED=false (kill-switch on; no paid Anthropic call)",
                    feature);
            return true;
        }
        return false;
    }
}
