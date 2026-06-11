package com.youtubeauto.video.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/** Step 7 (partial): probe final output for metadata. */
@Component
@RequiredArgsConstructor
public class MediaProbe {

    private final FfmpegRunner runner;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Info(double durationSeconds, String videoCodec, String audioCodec, int width, int height) {}

    public Info probe(Path file, Path workdir) {
        String json = runner.runFfprobe(List.of(
                "-v", "error",
                "-show_format", "-show_streams",
                "-of", "json",
                file.toString()
        ), workdir);

        try {
            JsonNode root = mapper.readTree(json);
            double dur = root.path("format").path("duration").asDouble();
            String vCodec = "", aCodec = "";
            int w = 0, h = 0;
            for (JsonNode s : root.path("streams")) {
                String type = s.path("codec_type").asText();
                if ("video".equals(type)) {
                    vCodec = s.path("codec_name").asText();
                    w = s.path("width").asInt();
                    h = s.path("height").asInt();
                } else if ("audio".equals(type)) {
                    aCodec = s.path("codec_name").asText();
                }
            }
            return new Info(dur, vCodec, aCodec, w, h);
        } catch (Exception e) {
            throw new IllegalStateException("ffprobe parse failed: " + e.getMessage(), e);
        }
    }
}
