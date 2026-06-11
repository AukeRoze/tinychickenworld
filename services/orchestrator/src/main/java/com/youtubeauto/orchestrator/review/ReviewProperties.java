package com.youtubeauto.orchestrator.review;

/**
 * Mirror of bible/channel.yml -> review block. Loaded once at startup
 * by ReviewConfigLoader.
 *
 * Note: the bible YAML key is still `notify` for readability, but the
 * Java component is named `mail` — `notify` would clash with
 * {@link Object#notify()} and the compiler refuses it as a record name.
 */
public record ReviewProperties(
        boolean afterScript,
        boolean reviewImages,
        boolean afterAssets,
        boolean beforeVeo,
        boolean beforeUpload,
        Mail mail
) {
    public record Mail(String to, String from, String baseUrl) {}

    public static ReviewProperties defaults() {
        return new ReviewProperties(
                true, true, false, false, true,
                new Mail("", "noreply@yt-pipeline.local", "http://localhost:8080")
        );
    }
}
