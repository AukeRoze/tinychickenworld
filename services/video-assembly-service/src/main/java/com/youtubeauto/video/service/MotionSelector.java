package com.youtubeauto.video.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks a MotionPreset per scene, avoiding immediate repeats inside a single
 * video. Configurable: {@code app.motion.enabled=false} forces ZOOM_IN
 * (legacy behaviour) for predictable testing.
 */
@Component
public class MotionSelector {

    private static final int AVOID_LAST = 2;

    @Value("${app.motion.enabled:true}")
    private boolean enabled;

    public MotionPicker startVideo() {
        return new MotionPicker(enabled);
    }

    /**
     * Direct camera-motion opposite — the pairing that produces a jarring
     * "bounce" over a cut (pan left then immediately pan right, zoom in then
     * straight back out). We avoid handing one scene off to its opposite so
     * the motion reads as continuous rather than ping-ponging.
     */
    static MotionPreset opposite(MotionPreset p) {
        if (p == null) return null;
        return switch (p) {
            case ZOOM_IN            -> MotionPreset.ZOOM_OUT;
            case ZOOM_OUT           -> MotionPreset.ZOOM_IN;
            case PAN_LEFT           -> MotionPreset.PAN_RIGHT;
            case PAN_RIGHT          -> MotionPreset.PAN_LEFT;
            case ZOOM_IN_PAN_RIGHT  -> MotionPreset.PAN_LEFT;   // reverse the pan
            case ZOOM_PAN_DIAGONAL  -> MotionPreset.ZOOM_OUT;   // reverse the zoom
        };
    }

    /** Stateful per-video picker — instantiate one per AssemblyService call. */
    public static class MotionPicker {
        private final boolean enabled;
        private final Deque<MotionPreset> recent = new ArrayDeque<>(AVOID_LAST);
        private MotionPreset last = null;

        MotionPicker(boolean enabled) { this.enabled = enabled; }

        public synchronized MotionPreset next() {
            if (!enabled) return MotionPreset.ZOOM_IN;
            MotionPreset[] all = MotionPreset.values();
            MotionPreset opp = opposite(last);
            for (int attempt = 0; attempt < 12; attempt++) {
                MotionPreset p = all[ThreadLocalRandom.current().nextInt(all.length)];
                // Reject recent repeats AND the direct opposite of the previous
                // scene's motion (anti-bounce / motion continuity across the cut).
                if (!recent.contains(p) && p != opp) {
                    pushRecent(p);
                    return p;
                }
            }
            MotionPreset fallback = all[ThreadLocalRandom.current().nextInt(all.length)];
            pushRecent(fallback);
            return fallback;
        }

        private void pushRecent(MotionPreset p) {
            if (recent.size() == AVOID_LAST) recent.removeFirst();
            recent.addLast(p);
            last = p;
        }
    }
}
