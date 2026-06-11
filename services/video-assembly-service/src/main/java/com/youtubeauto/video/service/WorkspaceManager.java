package com.youtubeauto.video.service;

import com.youtubeauto.video.config.VideoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WorkspaceManager {

    private final VideoProperties props;

    public Workspace create(UUID jobId) {
        try {
            Path root = Paths.get(props.storage().workRoot(), jobId.toString());
            Path tmp = root.resolve("tmp");
            Path out = root.resolve("out");
            Files.createDirectories(tmp);
            Files.createDirectories(out);
            return new Workspace(root, tmp, out);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create workspace", e);
        }
    }

    // INTERMEDIATES ARE MKV (assembly-audit #1): mp4 can't carry PCM audio, and
    // re-encoding AAC at every pipeline hop stacked 6+ lossy audio generations
    // onto the voice track. mkv intermediates carry h264 + pcm_s16le losslessly
    // between stages; only FinalEncoder (out/final.mp4) compresses to AAC, once.
    public record Workspace(Path root, Path tmp, Path out) {
        public Path sceneClip(int seq) { return tmp.resolve(String.format("scene_%02d.mkv", seq)); }
        public Path concatList()       { return tmp.resolve("concat.txt"); }
        public Path joined()           { return tmp.resolve("joined.mkv"); }
        public Path branded()          { return tmp.resolve("branded.mkv"); }
        public Path withMusic()        { return tmp.resolve("withmusic.mkv"); }
        public Path withSubs()         { return tmp.resolve("withsubs.mkv"); }
        public Path subs()             { return out.resolve("subs.srt"); }
        public Path finalOut()         { return out.resolve("final.mp4"); }
    }
}
