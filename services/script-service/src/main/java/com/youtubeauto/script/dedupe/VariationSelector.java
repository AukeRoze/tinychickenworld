package com.youtubeauto.script.dedupe;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks a random VariationProfile while avoiding profiles used in the
 * last few generations. With 5*5*5*4 = 500 combinations this is overkill
 * for collision avoidance, but pinning recent tag-tuples to a small ring
 * cheaply prevents same-profile-twice-in-a-row drift.
 */
@Component
public class VariationSelector {

    private static final int AVOID_LAST = 5;
    private final Deque<String> recent = new ArrayDeque<>(AVOID_LAST);

    public synchronized VariationProfile next() {
        for (int attempt = 0; attempt < 16; attempt++) {
            VariationProfile p = roll();
            if (!recent.contains(p.tag())) {
                pushRecent(p.tag());
                return p;
            }
        }
        VariationProfile fallback = roll();
        pushRecent(fallback.tag());
        return fallback;
    }

    /** Force a different profile after a duplicate hit. */
    public synchronized VariationProfile nextAvoiding(VariationProfile excluded) {
        for (int attempt = 0; attempt < 32; attempt++) {
            VariationProfile p = roll();
            if (!p.tag().equals(excluded.tag()) && !recent.contains(p.tag())) {
                pushRecent(p.tag());
                return p;
            }
        }
        VariationProfile p = roll();
        pushRecent(p.tag());
        return p;
    }

    private VariationProfile roll() {
        var r = ThreadLocalRandom.current();
        return new VariationProfile(
                pick(VariationProfile.Hook.values(), r),
                pick(VariationProfile.Tone.values(), r),
                pick(VariationProfile.Structure.values(), r),
                pick(VariationProfile.ExampleStyle.values(), r)
        );
    }

    private static <T> T pick(T[] values, java.util.concurrent.ThreadLocalRandom r) {
        return values[r.nextInt(values.length)];
    }

    private void pushRecent(String tag) {
        if (recent.size() == AVOID_LAST) recent.removeFirst();
        recent.addLast(tag);
    }
}
