package com.youtubeauto.script.dedupe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * 64-bit SimHash (Charikar). Cheap near-duplicate signature: tokenize text,
 * project each token's 64-bit hash into a +/-1 vector per bit, then take
 * the sign per bit as the final signature.
 *
 * Two texts are "near-duplicate" if their Hamming distance is small.
 * For 64-bit signatures: Hamming &lt;= 12 corresponds to ~80% similarity.
 *
 * Note: SimHash is a fast first pass. For ambiguous cases you'd
 * follow up with cosine similarity on embeddings.
 */
public final class SimHash {

    private static final Set<String> STOP = Set.of(
            "the","a","an","and","or","but","if","then","else","of","to","in","on","at","for",
            "with","is","are","was","were","be","been","being","this","that","these","those",
            "it","its","as","by","from","we","you","they","i","he","she","do","does","did",
            "have","has","had","not","no","so","very","just"
    );

    private SimHash() {}

    public static long compute(String text) {
        if (text == null || text.isBlank()) return 0L;
        int[] v = new int[64];
        boolean any = false;
        for (String token : tokenize(text)) {
            any = true;
            long h = token64(token);
            for (int i = 0; i < 64; i++) {
                v[i] += ((h >>> i) & 1L) == 1L ? 1 : -1;
            }
        }
        if (!any) return 0L;
        long sig = 0L;
        for (int i = 0; i < 64; i++) {
            if (v[i] > 0) sig |= (1L << i);
        }
        return sig;
    }

    public static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /** Convenience: similarity ratio in [0,1] derived from Hamming distance. */
    public static double similarity(long a, long b) {
        return 1.0 - (hamming(a, b) / 64.0);
    }

    private static Iterable<String> tokenize(String text) {
        String[] parts = text.toLowerCase().split("[^\\p{L}\\p{Nd}]+");
        java.util.List<String> out = new java.util.ArrayList<>(parts.length);
        for (String p : parts) {
            if (p.length() < 3) continue;
            if (STOP.contains(p)) continue;
            out.add(p);
        }
        return out;
    }

    private static long token64(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(token.getBytes(StandardCharsets.UTF_8));
            long v = 0L;
            for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xff);
            return v;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
