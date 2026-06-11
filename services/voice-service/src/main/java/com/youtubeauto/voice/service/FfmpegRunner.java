package com.youtubeauto.voice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight wrapper around the ffmpeg binary. The voice-service container
 * has ffmpeg installed; this just shells out with a fresh process per call.
 */
@Slf4j
@Component
public class FfmpegRunner {

    @Value("${app.ffmpeg-bin:ffmpeg}")
    private String ffmpegBin;

    public void run(List<String> args, Path workDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegBin);
        cmd.addAll(args);
        log.debug("ffmpeg {}", String.join(" ", args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workDir != null) pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                throw new IllegalStateException(
                        "ffmpeg exited " + code + " for args=" + args + "\n" + out);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("ffmpeg failed: " + e.getMessage(), e);
        }
    }
}
