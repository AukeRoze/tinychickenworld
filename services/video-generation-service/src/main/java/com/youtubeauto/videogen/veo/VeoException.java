package com.youtubeauto.videogen.veo;

public class VeoException extends RuntimeException {

    public enum Kind { QUOTA, TIMEOUT, OPERATION_FAILED, NETWORK, CORRUPT_OUTPUT, OTHER }

    private final Kind kind;

    public VeoException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public VeoException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
