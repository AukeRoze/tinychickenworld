package com.youtubeauto.upload.service;

import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Caption;
import com.google.api.services.youtube.model.CaptionSnippet;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;
import com.youtubeauto.upload.api.dto.UploadRequest;
import com.youtubeauto.upload.api.dto.UploadResponse;
import com.youtubeauto.upload.config.YouTubeProperties;
import com.youtubeauto.upload.youtube.YouTubeClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeUploadService {

    private final YouTubeClientFactory clientFactory;
    private final YouTubeProperties props;

    public UploadResponse upload(UploadRequest req) {
        YouTube yt = clientFactory.get();
        try {
            Video snippet = buildSnippet(req);
            File videoFile = new File(req.videoPath());
            if (!videoFile.exists()) throw new IllegalArgumentException("video not found: " + req.videoPath());

            InputStreamContent media = new InputStreamContent("video/*", new FileInputStream(videoFile));
            media.setLength(videoFile.length());

            YouTube.Videos.Insert insert = yt.videos()
                    .insert(List.of("snippet", "status"), snippet, media);

            // Enable resumable upload progress logging
            MediaHttpUploader uploader = insert.getMediaHttpUploader();
            uploader.setDirectUploadEnabled(false);
            uploader.setProgressListener(u -> log.info("upload {} {}%",
                    u.getUploadState(), Math.round(u.getProgress() * 100)));

            Video uploaded = insert.execute();
            String videoId = uploaded.getId();
            log.info("uploaded job={} -> youtube id={}", req.jobId(), videoId);

            if (req.thumbnailPath() != null && !req.thumbnailPath().isBlank()) {
                setThumbnail(yt, videoId, req.thumbnailPath());
            }

            Boolean captionsUploaded = null;
            if (req.captionsPath() != null && !req.captionsPath().isBlank()) {
                captionsUploaded = uploadCaptions(yt, videoId, req.captionsPath());
            }

            return new UploadResponse(
                    req.jobId(),
                    videoId,
                    "https://www.youtube.com/watch?v=" + videoId,
                    uploaded.getStatus().getPrivacyStatus(),
                    captionsUploaded
            );
        } catch (Exception e) {
            throw new IllegalStateException("YouTube upload failed: " + e.getMessage(), e);
        }
    }

    private Video buildSnippet(UploadRequest req) {
        VideoSnippet s = new VideoSnippet();
        s.setTitle(req.title());
        s.setDescription(req.description());
        s.setTags(req.tags() != null ? req.tags() : props.defaultTags());
        s.setCategoryId(req.categoryId() != null ? req.categoryId() : props.defaultCategoryId());

        VideoStatus st = new VideoStatus();
        // Scheduled publish: if publishAt is in the future, force privacy to
        // PRIVATE (YouTube requires it) and pass publishAt — YouTube will
        // auto-publish at that moment. Past/null publishAt = upload now with
        // the requested privacy.
        boolean scheduled = req.publishAt() != null
                && req.publishAt().isAfter(java.time.OffsetDateTime.now().plusMinutes(1));
        if (scheduled) {
            st.setPrivacyStatus("private");
            st.setPublishAt(new com.google.api.client.util.DateTime(
                    req.publishAt().toInstant().toEpochMilli()));
        } else {
            st.setPrivacyStatus(req.privacyStatus() != null ? req.privacyStatus() : props.defaultPrivacy());
        }
        // COPPA — locked to props value so it's set on every upload, never null.
        st.setSelfDeclaredMadeForKids(props.madeForKids());
        st.setMadeForKids(props.madeForKids());
        // YouTube synthetic-media disclosure (status.containsSyntheticMedia,
        // available since YouTube Data API rev 2024-10-30). Required when any
        // realistic A/S content is in the video; safe to enable for AI voices.
        st.setContainsSyntheticMedia(props.containsSyntheticMedia());

        Video v = new Video();
        v.setSnippet(s);
        v.setStatus(st);
        return v;
    }

    /** YouTube rejects thumbnails larger than 2 MiB (2,097,152 bytes). We aim
     *  a little under to leave headroom. */
    private static final long THUMB_LIMIT_BYTES = 2_000_000L;

    private void setThumbnail(YouTube yt, String videoId, String path) {
        try {
            File f = new File(path);
            if (!f.exists()) {
                log.warn("thumbnail not found, skipping: {}", path);
                return;
            }
            File toUpload = f;
            String mediaType = path.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            if (f.length() > THUMB_LIMIT_BYTES) {
                File smaller = compressToFit(f, THUMB_LIMIT_BYTES);
                if (smaller != null) {
                    log.info("thumbnail {} was {} bytes (over YouTube's 2MB limit) — "
                            + "recompressed to JPEG {} bytes", f.getName(), f.length(), smaller.length());
                    toUpload = smaller;
                    mediaType = "image/jpeg";
                }
            }
            FileContent media = new FileContent(mediaType, toUpload);
            yt.thumbnails().set(videoId, media).execute();
            log.info("thumbnail set for {}", videoId);
        } catch (Exception e) {
            log.warn("thumbnail upload failed for {}: {}", videoId, e.getMessage());
        }
    }

    /** Uploads the .srt as a toggleable English caption track. Best-effort —
     *  a caption failure must never fail the (successful) video upload. Requires
     *  the youtubepartner / youtube.force-ssl scope.
     *  @return true on success, false on any failure (so the orchestrator can
     *  surface it — without a manual track YouTube falls back to auto-captions,
     *  which audibly garble the channel's invented words: "tok tok" became
     *  "Tuk talk"/"Dock" on ep 3). */
    private boolean uploadCaptions(YouTube yt, String videoId, String srtPath) {
        try {
            File srt = new File(srtPath);
            if (!srt.exists()) {
                log.error("CAPTIONS MISSING — srt not found at {}; YouTube will use "
                        + "auto-generated captions for {}", srtPath, videoId);
                return false;
            }
            CaptionSnippet snip = new CaptionSnippet()
                    .setVideoId(videoId)
                    .setLanguage("en")
                    .setName("English");
            Caption caption = new Caption().setSnippet(snip);
            InputStreamContent media = new InputStreamContent(
                    "application/octet-stream", new FileInputStream(srt));
            media.setLength(srt.length());
            yt.captions().insert(List.of("snippet"), caption, media).execute();
            log.info("captions uploaded for {} ({} bytes)", videoId, srt.length());
            return true;
        } catch (Exception e) {
            // LOUD on purpose: a 403 here usually means the stored OAuth token
            // lacks the youtube.force-ssl scope — re-consent fixes it once.
            log.error("CAPTION UPLOAD FAILED for {} (video itself is fine; YouTube "
                    + "will show auto-generated captions): {}", videoId, e.getMessage());
            return false;
        }
    }

    /**
     * Re-encode an oversized thumbnail as JPEG, dropping quality until it fits
     * under {@code limitBytes}. Returns a temp file, or null if it couldn't be
     * read/encoded.
     */
    private File compressToFit(File src, long limitBytes) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(src);
            if (img == null) return null;
            // JPEG has no alpha channel — flatten onto an opaque background.
            java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(
                    img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgb.createGraphics();
            g.drawImage(img, 0, 0, java.awt.Color.BLACK, null);
            g.dispose();

            File best = null;
            for (float q = 0.9f; q >= 0.4f; q -= 0.1f) {
                File out = File.createTempFile("yt-thumb-", ".jpg");
                out.deleteOnExit();
                javax.imageio.ImageWriter writer =
                        javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
                javax.imageio.ImageWriteParam p = writer.getDefaultWriteParam();
                p.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(q);
                try (javax.imageio.stream.ImageOutputStream ios =
                             javax.imageio.ImageIO.createImageOutputStream(out)) {
                    writer.setOutput(ios);
                    writer.write(null, new javax.imageio.IIOImage(rgb, null, null), p);
                } finally {
                    writer.dispose();
                }
                best = out;
                if (out.length() <= limitBytes) return out;
            }
            return best;   // best effort, even if marginally over
        } catch (Exception e) {
            log.warn("thumbnail recompress failed: {}", e.getMessage());
            return null;
        }
    }
}
