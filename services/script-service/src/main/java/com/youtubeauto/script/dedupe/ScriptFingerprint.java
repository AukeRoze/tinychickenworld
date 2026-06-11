package com.youtubeauto.script.dedupe;

import com.youtubeauto.script.anthropic.GeneratedScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Stable fingerprint of a script's narrative content.
 * Uses hook + concatenation of all spoken line texts + cta.
 */
public final class ScriptFingerprint {

    public record Fingerprint(String contentHash, long simhash) {}

    private ScriptFingerprint() {}

    public static Fingerprint of(GeneratedScript script) {
        String normalised = normalise(script);
        return new Fingerprint(sha256Hex(normalised), SimHash.compute(normalised));
    }

    private static String normalise(GeneratedScript s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.hook()).append('\n');
        for (GeneratedScript.Scene scene : s.scenes()) {
            for (GeneratedScript.Line line : scene.lines()) {
                sb.append(line.text()).append('\n');
            }
        }
        sb.append(s.cta());
        return sb.toString().toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
