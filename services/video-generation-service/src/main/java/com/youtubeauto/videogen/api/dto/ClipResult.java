package com.youtubeauto.videogen.api.dto;

public record ClipResult(
        int seq,
        String status,         // OK | FALLBACK | FAILED
        String clipPath,       // null on FALLBACK/FAILED
        String model,
        String resolution,
        int durationSeconds,
        long wallclockMs,
        double costEur,
        String error
) {
    public static ClipResult ok(int seq, String path, String model, String res,
                                int dur, long ms, double cost) {
        return new ClipResult(seq, "OK", path, model, res, dur, ms, cost, null);
    }
    public static ClipResult fallback(int seq, String model, String reason) {
        return new ClipResult(seq, "FALLBACK", null, model, null, 0, 0L, 0.0, reason);
    }
    public static ClipResult failed(int seq, String model, String reason) {
        return new ClipResult(seq, "FAILED", null, model, null, 0, 0L, 0.0, reason);
    }
}
