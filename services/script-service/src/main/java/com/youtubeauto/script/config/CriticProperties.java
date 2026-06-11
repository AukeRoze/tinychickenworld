package com.youtubeauto.script.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the cheap text "script critic" pass that runs BEFORE the render.
 * It is far cheaper to score story arc / re-hook cadence / satisfying ending /
 * age language and re-write once here than to discover a weak story only after a
 * full (expensive) render.
 */
@ConfigurationProperties(prefix = "app.critic")
public record CriticProperties(
        /** Master switch. Default on. */
        Boolean enabled,
        /** Overall score (0-100) below which a targeted rewrite is triggered. */
        Integer minScore,
        /** Max critic-driven rewrites per job (separate from the dedupe retries
         *  so a fussy critic can't starve the whole retry budget). */
        Integer maxRewrites
) {
    public boolean isEnabled()  { return enabled == null || enabled; }
    public int minScoreOr(int d){ return minScore == null ? d : minScore; }
    public int maxRewritesOr(int d){ return maxRewrites == null ? d : maxRewrites; }
}
