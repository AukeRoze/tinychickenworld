package com.youtubeauto.videogen.gcs;

/**
 * Tiny parser for gs://bucket/object/path URIs.
 */
public record GcsUri(String bucket, String object) {

    public static GcsUri parse(String uri) {
        if (uri == null || !uri.startsWith("gs://"))
            throw new IllegalArgumentException("Not a gs:// URI: " + uri);
        String rest = uri.substring("gs://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) return new GcsUri(rest, "");
        return new GcsUri(rest.substring(0, slash), rest.substring(slash + 1));
    }

    public String toUri() {
        return "gs://" + bucket + "/" + object;
    }
}
