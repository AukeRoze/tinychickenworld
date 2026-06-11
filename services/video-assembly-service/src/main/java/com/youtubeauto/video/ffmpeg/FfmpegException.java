package com.youtubeauto.video.ffmpeg;

public class FfmpegException extends RuntimeException {
    private final int exitCode;
    private final String tailStderr;
    private final String command;

    public FfmpegException(int exitCode, String command, String tailStderr) {
        super("ffmpeg failed (exit=" + exitCode + "): " + command + "\n--- stderr tail ---\n" + tailStderr);
        this.exitCode = exitCode;
        this.command = command;
        this.tailStderr = tailStderr;
    }
    public int exitCode() { return exitCode; }
    public String tailStderr() { return tailStderr; }
    public String command() { return command; }
}
