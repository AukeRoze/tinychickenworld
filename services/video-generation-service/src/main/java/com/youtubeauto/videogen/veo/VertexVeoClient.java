package com.youtubeauto.videogen.veo;

import com.google.genai.Client;
import com.google.genai.types.GenerateVideosConfig;
import com.google.genai.types.GenerateVideosOperation;
import com.google.genai.types.GenerateVideosResponse;
import com.google.genai.types.GeneratedVideo;
import com.google.genai.types.Image;
import com.google.genai.types.Video;
import com.youtubeauto.videogen.config.VeoProperties;
import com.youtubeauto.videogen.routing.ModelRoute;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the Google Gen AI Java SDK. Submits a Vertex AI
 * Veo image-to-video LRO and polls until terminal.
 *
 * The returned URI is a gs:// path under the requested outputGcsUri.
 * Callers download it to the local workdir.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${gcp.project-id:}' != ''")
public class VertexVeoClient {

    private final Client genai;
    private final VeoProperties veo;

    public VertexVeoClient(Client genai, VeoProperties veo) {
        this.genai = genai;
        this.veo = veo;
    }

    /**
     * Generates one clip and blocks until the Veo operation is terminal.
     *
     * <p>P2 — end-frame robustness. When an {@code endImageGcsUri} is supplied we
     * ask Veo to interpolate start→end (directed motion). {@code lastFrame(Image)}
     * is confirmed present in the pinned google-genai SDK (1.15.0), but last-frame
     * is <em>not</em> supported on every Veo model (e.g. the GA {@code veo-3.0}
     * fallback). So if an end-frame call fails for a non-transient reason, we retry
     * <strong>start-only</strong> — the scene still gets real Veo motion instead of
     * degrading all the way to Ken Burns. Quota / timeout / network errors are not
     * last-frame problems and are rethrown unchanged for the caller's own handling.
     */
    /** Back-compat overload — no character reference images. */
    public String generateAndAwait(
            ModelRoute route,
            String prompt,
            String startImageGcsUri,
            String endImageGcsUri,
            String negativePrompt,
            String aspectRatio,
            String outputGcsUri
    ) throws InterruptedException {
        return generateAndAwait(route, prompt, startImageGcsUri, endImageGcsUri,
                negativePrompt, aspectRatio, outputGcsUri, java.util.List.of());
    }

    /**
     * @param referenceImages optional character reference stills (≤3), attached
     *        as Veo 3.1 "asset" reference images so identity is anchored in
     *        pixels. Same robustness pattern as last-frame: not every model
     *        accepts them, so a non-transient failure retries WITHOUT refs
     *        (and finally without the end frame) before giving up.
     */
    public String generateAndAwait(
            ModelRoute route,
            String prompt,
            String startImageGcsUri,
            String endImageGcsUri,     // optional last-frame for directed motion; null = start-only
            String negativePrompt,
            String aspectRatio,        // "16:9" | "9:16"
            String outputGcsUri,       // gs://bucket/prefix/
            java.util.List<java.nio.file.Path> referenceImages
    ) throws InterruptedException {

        boolean withEndFrame = endImageGcsUri != null && !endImageGcsUri.isBlank();
        boolean withRefs = referenceImages != null && !referenceImages.isEmpty();
        try {
            return submitAndPoll(route, prompt, startImageGcsUri,
                    withEndFrame ? endImageGcsUri : null,
                    negativePrompt, aspectRatio, outputGcsUri,
                    withRefs ? referenceImages : java.util.List.of());
        } catch (VeoException e) {
            boolean mayBeUnsupported = e.kind() == VeoException.Kind.OTHER
                    || e.kind() == VeoException.Kind.OPERATION_FAILED;
            if (withRefs && mayBeUnsupported) {
                log.warn("Veo reference-image call failed ({}: {}) — retrying without refs "
                        + "for model {}", e.kind(), e.getMessage(), route.modelId());
                return generateAndAwait(route, prompt, startImageGcsUri, endImageGcsUri,
                        negativePrompt, aspectRatio, outputGcsUri, java.util.List.of());
            }
            if (withEndFrame && mayBeUnsupported) {
                log.warn("Veo end-frame call failed ({}: {}) — retrying start-only for model {}",
                        e.kind(), e.getMessage(), route.modelId());
                return submitAndPoll(route, prompt, startImageGcsUri, null,
                        negativePrompt, aspectRatio, outputGcsUri, java.util.List.of());
            }
            throw e;
        }
    }

    /** Builds the config, submits the generateVideos LRO and polls until done.
     *  {@code endImageGcsUri == null} = start-only (no last frame). */
    private String submitAndPoll(
            ModelRoute route,
            String prompt,
            String startImageGcsUri,
            String endImageGcsUri,
            String negativePrompt,
            String aspectRatio,
            String outputGcsUri,
            java.util.List<java.nio.file.Path> referenceImages
    ) throws InterruptedException {

        GenerateVideosConfig.Builder cfgBuilder = GenerateVideosConfig.builder()
                .aspectRatio(aspectRatio)
                .durationSeconds(route.durationSec())
                .resolution(route.resolution())
                .outputGcsUri(outputGcsUri);
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            cfgBuilder.negativePrompt(negativePrompt);
        }
        // Character reference stills (Veo 3.1 "asset" references) — identity
        // anchored in pixels. Sent inline (bytes) so no extra GCS round-trip.
        // If the SDK/model rejects them, generateAndAwait retries without refs.
        if (referenceImages != null && !referenceImages.isEmpty()) {
            java.util.List<com.google.genai.types.VideoGenerationReferenceImage> refs =
                    new java.util.ArrayList<>();
            for (java.nio.file.Path p : referenceImages) {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(p);
                    String mime = p.toString().toLowerCase().endsWith(".png")
                            ? "image/png" : "image/jpeg";
                    refs.add(com.google.genai.types.VideoGenerationReferenceImage.builder()
                            .image(Image.builder().imageBytes(bytes).mimeType(mime).build())
                            .referenceType("asset")
                            .build());
                } catch (Exception readErr) {
                    log.warn("Could not read reference image {} ({}) — skipping",
                            p, readErr.getMessage());
                }
            }
            if (!refs.isEmpty()) {
                cfgBuilder.referenceImages(refs);
                log.info("Veo call carries {} character reference image(s)", refs.size());
            }
        }
        // Optional end-pose frame → Veo interpolates start→end (directed motion).
        // VERIFIED against google-genai 1.15.0 source: GenerateVideosConfig.Builder
        // has lastFrame(Image) (image-to-video only). If a model rejects it,
        // generateAndAwait retries start-only rather than failing the scene.
        if (endImageGcsUri != null && !endImageGcsUri.isBlank()) {
            Image endImage = Image.builder()
                    .gcsUri(endImageGcsUri)
                    .mimeType("image/png")
                    .build();
            cfgBuilder.lastFrame(endImage);
        }

        Image startImage = Image.builder()
                .gcsUri(startImageGcsUri)
                .mimeType("image/png")
                .build();

        GenerateVideosOperation op;
        try {
            op = genai.models.generateVideos(route.modelId(), prompt, startImage, cfgBuilder.build());
        } catch (Exception e) {
            throw classify(e);
        }

        long deadline = System.currentTimeMillis() + veo.polling().maxWaitSeconds() * 1000L;
        long delay = veo.polling().initialDelayMs();

        while (!op.done().orElse(false)) {
            if (System.currentTimeMillis() > deadline) {
                throw new VeoException(VeoException.Kind.TIMEOUT,
                        "Veo operation did not finish within " + veo.polling().maxWaitSeconds() + "s");
            }
            Thread.sleep(delay);
            delay = Math.min(delay * 2, veo.polling().maxDelayMs());
            try {
                // Second arg is GetOperationConfig — null is the documented "no overrides" form.
                op = genai.operations.getVideosOperation(op, null);
            } catch (Exception e) {
                throw classify(e);
            }
        }

        if (op.error().isPresent()) {
            throw new VeoException(VeoException.Kind.OPERATION_FAILED,
                    "Veo op failed: " + op.error().get());
        }

        return op.response()
                .flatMap(GenerateVideosResponse::generatedVideos)
                .flatMap(v -> v.stream().findFirst())
                .flatMap(GeneratedVideo::video)
                .flatMap(Video::uri)
                .orElseThrow(() -> new VeoException(VeoException.Kind.OPERATION_FAILED,
                        "Veo response had no video URI"));
    }

    private VeoException classify(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("429") || msg.contains("quota") || msg.contains("rate limit")) {
            return new VeoException(VeoException.Kind.QUOTA, e.getMessage(), e);
        }
        if (msg.contains("timeout") || msg.contains("deadline")) {
            return new VeoException(VeoException.Kind.TIMEOUT, e.getMessage(), e);
        }
        if (e instanceof java.io.IOException) {
            return new VeoException(VeoException.Kind.NETWORK, e.getMessage(), e);
        }
        return new VeoException(VeoException.Kind.OTHER, e.getMessage(), e);
    }
}
