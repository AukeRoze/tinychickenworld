package com.youtubeauto.videogen.local;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * SCAFFOLD — lokale image-to-video via ComfyUI (LTX-2 / Wan op een eigen GPU).
 *
 * <p>Zelfde in/out-contract als {@code FalSeedanceClient}: startbeeld + prompt →
 * {@code clip.mp4}. {@link com.youtubeauto.videogen.service.ClipGenerationService}
 * routeert hierheen wanneer de model-id met {@code "local"} begint.
 *
 * <p><b>Dormant tot configuratie.</b> Zolang er geen ComfyUI draait (of
 * {@code local.enabled=false}) gooit deze client een duidelijke
 * {@link IllegalStateException}; de ClipGeneration-catch vangt die op en valt
 * terug op Ken Burns — geen crash, geen gedragsverandering voor de live pipeline.
 *
 * <p><b>Afmaken zodra ComfyUI draait</b> (zie {@code infra/local-video/INTEGRATION-PLAN.md}):
 * <ol>
 *   <li>laad workflow-template {@code {workflowDir}/<model>.json};</li>
 *   <li>injecteer startbeeld + prompt + duur/aspect/resolutie in de juiste nodes;</li>
 *   <li>{@code POST {baseUrl}/prompt} → prompt_id;</li>
 *   <li>poll {@code {baseUrl}/history/{prompt_id}} tot klaar;</li>
 *   <li>download de output-mp4 via {@code {baseUrl}/view} → {@code outFile}.</li>
 * </ol>
 * Model wisselen (LTX/Wan/Hunyuan) = andere workflow-JSON, geen codewijziging.
 */
@Slf4j
@Component
public class LocalVideoClient {

    @Value("${local.enabled:false}")
    private boolean enabled;
    @Value("${local.base-url:http://host.docker.internal:8188}")
    private String baseUrl;
    @Value("${local.workflow-dir:/bible/comfy-workflows}")
    private String workflowDir;
    @Value("${local.poll-interval-ms:3000}")
    private long pollIntervalMs;

    /** True wanneer de lokale provider aanstaat (config). */
    public boolean configured() {
        return enabled;
    }

    /**
     * Mirror of {@code FalSeedanceClient.generateAndDownload}: maak één clip uit
     * het startbeeld + prompt en schrijf 'm naar {@code outFile}.
     *
     * <p>SCAFFOLD: nog niet geïmplementeerd — wordt afgemaakt tegen een draaiende
     * ComfyUI (de node-ids/payload hangen af van de gekozen workflow).
     */
    public void generateAndDownload(String model, String prompt,
                                    Path startImage, Path endImage,
                                    String resolution, int durationSec, String aspect,
                                    Path outFile) throws IOException, InterruptedException {
        if (!enabled) {
            throw new IllegalStateException(
                    "Local ComfyUI provider disabled (local.enabled=false) — "
                    + "zet local.enabled=true zodra ComfyUI draait.");
        }
        // TODO (afmaken tegen draaiende ComfyUI — zie INTEGRATION-PLAN.md):
        //   1) laad {workflowDir}/{model}.json
        //   2) injecteer startImage + prompt + durationSec/aspect/resolution
        //   3) POST {baseUrl}/prompt   → prompt_id
        //   4) poll {baseUrl}/history/{prompt_id} elke pollIntervalMs tot klaar
        //   5) download /view → outFile
        log.warn("LocalVideoClient aangeroepen (model={}, baseUrl={}) maar nog niet "
                + "geïmplementeerd — val terug op Ken Burns.", model, baseUrl);
        throw new IllegalStateException(
                "Local ComfyUI client not yet implemented — finalize the workflow injection "
                + "against your running ComfyUI at " + baseUrl
                + " (see infra/local-video/INTEGRATION-PLAN.md). Workflow dir: " + workflowDir
                + ", poll=" + pollIntervalMs + "ms.");
    }
}
