package com.youtubeauto.script.dedupe;

import java.util.UUID;

public class DuplicateDetectionException extends RuntimeException {
    private final UUID conflictsWith;
    private final int hammingDistance;
    private final double similarity;
    private final boolean exact;

    public DuplicateDetectionException(UUID conflictsWith, int hammingDistance,
                                       double similarity, boolean exact) {
        super(String.format(
                "Generated script is a near-duplicate of script %s (Hamming=%d, similarity=%.3f, exact=%b)",
                conflictsWith, hammingDistance, similarity, exact));
        this.conflictsWith = conflictsWith;
        this.hammingDistance = hammingDistance;
        this.similarity = similarity;
        this.exact = exact;
    }

    public UUID conflictsWith() { return conflictsWith; }
    public int hammingDistance() { return hammingDistance; }
    public double similarity() { return similarity; }
    public boolean exact() { return exact; }
}
