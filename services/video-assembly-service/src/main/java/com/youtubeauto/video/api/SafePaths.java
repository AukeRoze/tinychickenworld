package com.youtubeauto.video.api;

import java.nio.file.Path;

/**
 * Boundary check for file paths that arrive over HTTP (clipPath, voiceLines,
 * videoPath, …). The assembly service only ever needs to read/write inside its
 * two mounts — the shared {@code /workdir} and the (mostly read-only)
 * {@code /bible}. Anything else (e.g. {@code /secrets/...}, {@code /etc/...})
 * is a request we should refuse, not serve. Throws IllegalArgumentException,
 * which the controllers surface as a 400 with the message.
 */
final class SafePaths {

    private static final Path WORKDIR = Path.of("/workdir");
    private static final Path BIBLE = Path.of("/bible");

    private SafePaths() {}

    static void requireMounted(String path) {
        if (path == null || path.isBlank()) return; // optional fields stay optional
        Path p = Path.of(path).normalize();
        if (!p.startsWith(WORKDIR) && !p.startsWith(BIBLE)) {
            throw new IllegalArgumentException(
                    "path must live under /workdir or /bible (was: " + p + ")");
        }
    }
}
