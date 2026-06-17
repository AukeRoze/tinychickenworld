package com.youtubeauto.orchestrator.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only diagnostics for runtime feature flags. Lets us verify which build is
 * actually running and what the Veo end-frame kill-switch resolves to in the
 * LIVE container — without spending Veo credits on a probe clip.
 *
 * <p>A 404 on this path means the running orchestrator predates this endpoint
 * (i.e. the kill-switch build was not deployed yet). A 200 with
 * {@code endFrameEnabled=false} confirms the master kill-switch (feedback
 * 2026-06-14: "haal het overal maar uit") is active in the running process, so
 * every Veo run is start-only and re-rolled clips will no longer carry an end
 * frame. See {@code PipelineOrchestrator#generateEndStills}.
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemFlagsController {

    /** Master kill-switch. Mirrors the binding in PipelineOrchestrator. */
    @Value("${app.veo.end-frame-enabled:${VEO_END_FRAME:false}}")
    private boolean endFrameEnabled;

    /** Secondary flag: only meaningful when the master is true. */
    @Value("${app.veo.end-frame-all-scenes:${VEO_END_FRAME_ALL:true}}")
    private boolean endFrameAllScenes;

    @GetMapping("/veo-flags")
    public Map<String, Object> veoFlags() {
        return Map.of(
                "endFrameEnabled", endFrameEnabled,
                "endFrameAllScenes", endFrameAllScenes,
                // Effective behaviour: end frames are produced only when the
                // master is on. Start-only everywhere otherwise.
                "endFramesActive", endFrameEnabled);
    }
}
