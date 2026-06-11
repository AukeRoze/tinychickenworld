package com.youtubeauto.videogen.cost;

import com.youtubeauto.videogen.config.VeoProperties;
import com.youtubeauto.videogen.routing.ModelRoute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Estimates the EUR cost of a Veo render before submission. Rates come
 * from veo.rates in application.yml. An unknown model id has no configured
 * rate; rather than silently guessing low (which would let the per-video cost
 * cap protect too late), we log loudly and use a CONSERVATIVE-HIGH default so
 * the cap kicks in early. The fix is to add the missing rate to veo.rates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostCalculator {

    /** Conservative-high fallback (€/s) for a model with no configured rate.
     *  Deliberately above the real Veo tiers so the cost cap errs on the safe
     *  side instead of under-counting an unpriced model. */
    private static final double UNKNOWN_RATE_EUR_PER_SEC = 0.50;

    private final VeoProperties veo;

    public double estimate(ModelRoute route) {
        String key = route.modelId();
        VeoProperties.Rate rate = veo.rates() != null ? veo.rates().get(key) : null;
        double perSecond;
        if (rate != null) {
            perSecond = rate.eurPerSecond();
        } else {
            perSecond = UNKNOWN_RATE_EUR_PER_SEC;
            log.warn("No Veo rate configured for model '{}' — using conservative €{}/s for the "
                    + "cost cap. Add it to veo.rates in application.yml.", key, perSecond);
        }
        return perSecond * route.durationSec();
    }
}
