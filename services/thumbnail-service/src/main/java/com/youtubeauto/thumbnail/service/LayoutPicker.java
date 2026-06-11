package com.youtubeauto.thumbnail.service;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

/** Picks a LayoutTemplate, avoiding the last few used. */
@Component
public class LayoutPicker {

    private static final int AVOID_LAST = 2;
    private final Deque<LayoutTemplate> recent = new ArrayDeque<>(AVOID_LAST);

    /** Face-driven rotation for the kids vertical (audience can't read):
     *  variant 1 = pure face (safe winner), variant 2 = face + tiny badge,
     *  variant 3 = pure face with a different mood/crop. Deterministic so the
     *  three variants are comparable across episodes once CTR analytics land. */
    private static final LayoutTemplate[] FACE_DRIVEN = {
            LayoutTemplate.NO_TEXT,
            LayoutTemplate.BADGE_BOTTOM_LEFT,
            LayoutTemplate.NO_TEXT
    };

    public LayoutTemplate pickFaceDriven(int variant) {
        return FACE_DRIVEN[Math.floorMod(variant - 1, FACE_DRIVEN.length)];
    }

    public synchronized LayoutTemplate pick() {
        LayoutTemplate[] all = LayoutTemplate.values();
        for (int attempt = 0; attempt < 8; attempt++) {
            LayoutTemplate p = all[ThreadLocalRandom.current().nextInt(all.length)];
            if (!recent.contains(p)) {
                push(p);
                return p;
            }
        }
        LayoutTemplate fallback = all[ThreadLocalRandom.current().nextInt(all.length)];
        push(fallback);
        return fallback;
    }

    private void push(LayoutTemplate p) {
        if (recent.size() == AVOID_LAST) recent.removeFirst();
        recent.addLast(p);
    }
}
