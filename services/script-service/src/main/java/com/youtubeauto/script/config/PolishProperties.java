package com.youtubeauto.script.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the OPTIONAL dialogue punch-up pass. After the cheap (Haiku) script
 * generation + critic gates settle on a final script, this pass runs ONE call on
 * a stronger model that rewrites ONLY the spoken lines — sharpening humour,
 * timing and the callback/payoff — while leaving structure, visuals, durations
 * and cast untouched. It is a deliberate, bounded extra spend (one call per
 * video), so it ships OFF by default and is flipped on per channel via env.
 */
@ConfigurationProperties(prefix = "app.polish")
public record PolishProperties(
        /** Master switch. Default OFF (it costs a pricier call per video). */
        Boolean enabled,
        /** Stronger model id for the punch-up (e.g. a Sonnet). Falls back to the
         *  default script model when blank. */
        String model,
        /** Sampling temperature for the punch-up — a touch higher for wit. */
        Double temperature
) {
    public boolean isEnabled() { return enabled != null && enabled; }
    public String modelOrNull() { return (model == null || model.isBlank()) ? null : model; }
    public Double temperatureOrNull() { return temperature; }
}
