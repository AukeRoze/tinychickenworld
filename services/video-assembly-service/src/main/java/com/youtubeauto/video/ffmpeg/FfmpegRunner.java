package com.youtubeauto.video.ffmpeg;

import com.youtubeauto.video.config.VideoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Thin wrapper around ProcessBuilder for ffmpeg/ffprobe invocations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegRunner {

    private final VideoProperties props;

    /** Global encode governor: caps how many ffmpeg ENCODE/DECODE processes run
     *  at once, regardless of which thread-pool spawned them — parallel scene
     *  encodes stacking up is what OOM-killed the ep-3 render (exit=137).
     *  ffprobe calls are not gated (metadata reads are IO-light). */
    @org.springframework.beans.factory.annotation.Value("${app.ffmpeg.max-concurrent:3}")
    private int maxConcurrent;
    private volatile java.util.concurrent.Semaphore gate;

    private java.util.concurrent.Semaphore gate() {
        java.util.concurrent.Semaphore g = gate;
        if (g == null) {
            synchronized (this) {
                if (gate == null) {
                    gate = new java.util.concurrent.Semaphore(Math.max(1, maxConcurrent), true);
                }
                g = gate;
            }
        }
        return g;
    }

    public void runFfmpeg(List<String> args, Path workdir) {
        java.util.concurrent.Semaphore g = gate();
        try {
            g.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FfmpegException(-1, "ffmpeg", "interrupted waiting for encode slot");
        }
        try {
            run(prefixBinary(props.ffmpeg().binary(), args), workdir);
        } finally {
            g.release();
        }
    }

    public String runFfprobe(List<String> args, Path workdir) {
        return runCaptured(prefixBinary(props.ffmpeg().probeBinary(), args), workdir);
    }

    /** Runs ffmpeg and returns its merged stdout+stderr — used for the loudnorm
     *  measurement pass, whose JSON report is printed to stderr. Gated like
     *  encodes: a loudness scan decodes the whole file. */
    public String runFfmpegCaptured(List<String> args, Path workdir) {
        java.util.concurrent.Semaphore g = gate();
        try {
            g.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FfmpegException(-1, "ffmpeg", "interrupted waiting for encode slot");
        }
        try {
            return runCaptured(prefixBinary(props.ffmpeg().binary(), args), workdir);
        } finally {
            g.release();
        }
    }

    private void run(List<String> cmd, Path workdir) {
        long t0 = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workdir.toFile())
                .redirectErrorStream(false);

        log.info("exec: {}", String.join(" ", cmd));
        Deque<String> tail = new ArrayDeque<>(200);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new FfmpegException(-1, String.join(" ", cmd), e.getMessage());
        }

        // Drain stderr so the process doesn't block on a full pipe.
        Thread drain = Thread.startVirtualThread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (tail.size() == 200) tail.removeFirst();
                    tail.addLast(line);
                }
            } catch (IOException ignored) {}
        });

        boolean finished;
        try {
            finished = p.waitFor(props.ffmpeg().perInvocationTimeoutMinutes(), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new FfmpegException(-1, String.join(" ", cmd), "interrupted");
        }
        if (!finished) {
            p.destroyForcibly();
            throw new FfmpegException(-1, String.join(" ", cmd), "timeout");
        }
        try { drain.join(1000); } catch (InterruptedException ignored) {}

        if (p.exitValue() != 0) {
            throw new FfmpegException(p.exitValue(), String.join(" ", cmd), String.join("\n", tail));
        }
        log.info("ok in {}ms", System.currentTimeMillis() - t0);
    }

    private String runCaptured(List<String> cmd, Path workdir) {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workdir.toFile()).redirectErrorStream(true);
        try {
            Process p = pb.start();
            String output;
            try (var in = p.getInputStream()) {
                output = new String(in.readAllBytes());
            }
            if (!p.waitFor(2, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new FfmpegException(-1, String.join(" ", cmd), "ffprobe timeout");
            }
            if (p.exitValue() != 0) {
                throw new FfmpegException(p.exitValue(), String.join(" ", cmd), output);
            }
            return output;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new FfmpegException(-1, String.join(" ", cmd), e.getMessage());
        }
    }

    private List<String> prefixBinary(String binary, List<String> args) {
        var out = new java.util.ArrayList<String>(args.size() + 1);
        out.add(binary);
        out.addAll(args);
        return out;
    }
}
