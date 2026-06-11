package com.youtubeauto.videogen.gcs;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.youtubeauto.videogen.config.GcpProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Uploads start-images and downloads rendered clips. Bucket name +
 * prefix come from GcpProperties so callers only deal with logical paths.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${gcp.project-id:}' != ''")
public class GcsClient {

    private final Storage storage;
    private final GcpProperties gcp;

    public GcsClient(Storage storage, GcpProperties gcp) {
        this.storage = storage;
        this.gcp = gcp;
    }

    public String uploadImage(UUID jobId, int seq, Path imagePath, String mimeType) throws IOException {
        String objectName = "%s/refs/%s/scene-%d.png".formatted(gcp.outputPrefix(), jobId, seq);
        BlobId id = BlobId.of(gcp.outputBucket(), objectName);
        BlobInfo info = BlobInfo.newBuilder(id).setContentType(mimeType).build();
        storage.createFrom(info, imagePath);
        String uri = "gs://" + gcp.outputBucket() + "/" + objectName;
        log.debug("Uploaded start-image for job={} seq={} -> {}", jobId, seq, uri);
        return uri;
    }

    /** Uploads the optional end-pose (last-frame) image under a distinct object
     *  name so it never clobbers the start-image. */
    public String uploadEndImage(UUID jobId, int seq, Path imagePath, String mimeType) throws IOException {
        String objectName = "%s/refs/%s/scene-%d-end.png".formatted(gcp.outputPrefix(), jobId, seq);
        BlobId id = BlobId.of(gcp.outputBucket(), objectName);
        BlobInfo info = BlobInfo.newBuilder(id).setContentType(mimeType).build();
        storage.createFrom(info, imagePath);
        String uri = "gs://" + gcp.outputBucket() + "/" + objectName;
        log.debug("Uploaded end-image for job={} seq={} -> {}", jobId, seq, uri);
        return uri;
    }

    /** Directory-style URI for Veo to write its output into. */
    public String outputPrefixUri(UUID jobId, int seq) {
        return "gs://" + gcp.outputBucket() + "/"
                + gcp.outputPrefix() + "/out/" + jobId + "/scene-" + seq + "/";
    }

    /** Downloads a gs://… object to a local file. */
    public void download(String gcsUri, Path localPath) throws IOException {
        GcsUri u = GcsUri.parse(gcsUri);
        Blob blob = storage.get(BlobId.of(u.bucket(), u.object()));
        if (blob == null) throw new IOException("GCS object not found: " + gcsUri);
        Files.createDirectories(localPath.getParent());
        try (ReadableByteChannel src = blob.reader();
             OutputStream dst = Files.newOutputStream(localPath)) {
            byte[] buf = new byte[64 * 1024];
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf);
            int n;
            while ((n = src.read(bb)) >= 0) {
                if (n > 0) dst.write(buf, 0, n);
                bb.clear();
            }
        }
        log.debug("Downloaded {} -> {} ({} bytes)", gcsUri, localPath, Files.size(localPath));
    }

    /** Best-effort cleanup. Caller should ignore errors. */
    public void deleteQuietly(String gcsUri) {
        try {
            GcsUri u = GcsUri.parse(gcsUri);
            storage.delete(BlobId.of(u.bucket(), u.object()));
        } catch (Exception e) {
            log.debug("GCS delete failed for {}: {}", gcsUri, e.getMessage());
        }
    }
}
