package com.youtubeauto.voice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Concatenates multiple MP3 line clips into one MP3 per scene using FFmpeg's
 * concat demuxer with stream copy — no re-encode, very fast.
 */
@Slf4j
@Component
public class AudioConcat {

    public void concat(List<Path> inputs, Path output, Path workdir) {
        if (inputs.size() == 1) {
            try {
                Files.copy(inputs.get(0), output, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to copy single line: " + e.getMessage(), e);
            }
        }

        Path list = output.resolveSibling(output.getFileName() + ".concat.txt");
        try {
            String body = inputs.stream()
                    .map(p -> "file '" + p.toAbsolutePath().toString().replace("'", "\\'") + "'")
                    .collect(Collectors.joining("\n"));
            Files.writeString(list, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write concat list", e);
        }

        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-y",
                "-f", "concat", "-safe", "0",
                "-i", list.toString(),
                "-c", "copy",
                output.toString()
        ));

        try {
            log.debug("audio concat: {}", String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workdir.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            String stdoutErr = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("ffmpeg concat timed out");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg concat failed (exit=" + p.exitValue()
                        + "): " + stdoutErr);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("ffmpeg concat error: " + e.getMessage(), e);
        } finally {
            try { Files.deleteIfExists(list); } catch (IOException ignored) {}
        }
    }
}
