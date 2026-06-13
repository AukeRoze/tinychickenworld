package com.youtubeauto.videogen.cost;

import com.youtubeauto.videogen.config.VeoProperties;
import com.youtubeauto.videogen.routing.ModelRoute;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P7 — guards the Veo cost estimate, including the P8 behaviour: an unpriced
 * model must NOT silently estimate low (which would let the per-video cost cap
 * protect too late) but use a conservative-high default.
 */
class CostCalculatorTest {

    private CostCalculator calcWithRates(Map<String, VeoProperties.Rate> rates) {
        // Only rates() is used by estimate(); polling/parallelism/quota are irrelevant here.
        return new CostCalculator(new VeoProperties(null, null, rates, null));
    }

    @Test
    void knownModel_usesConfiguredRate() {
        CostCalculator calc = calcWithRates(Map.of(
                "veo-3.1-fast-generate-preview", new VeoProperties.Rate(0.10)));
        double cost = calc.estimate(new ModelRoute("veo-3.1-fast-generate-preview", "720p", 6));
        assertEquals(0.60, cost, 1e-9);   // 0.10 €/s * 6s
    }

    @Test
    void unknownModel_usesConservativeHighDefault_notSilentLow() {
        CostCalculator calc = calcWithRates(Map.of(
                "veo-3.1-fast-generate-preview", new VeoProperties.Rate(0.10)));
        double cost = calc.estimate(new ModelRoute("some-unpriced-model", "1080p", 4));
        assertEquals(2.00, cost, 1e-9);   // 0.50 €/s conservative default * 4s (NOT 0.20)
    }

    @Test
    void nullRatesMap_fallsBackToConservativeDefault() {
        CostCalculator calc = calcWithRates(null);
        double cost = calc.estimate(new ModelRoute("anything", "720p", 8));
        assertEquals(4.00, cost, 1e-9);   // 0.50 €/s * 8s
    }
}
