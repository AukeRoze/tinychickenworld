package com.youtubeauto.videogen.routing;

import com.youtubeauto.videogen.bible.BibleLoader;
import com.youtubeauto.videogen.bible.VideoGenConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Picks a model + resolution + duration per scene, applying bible-driven
 * routing and cost-cap downshifts.
 *
 * Vertex AI model IDs use hyphens; we translate the bible's underscores
 * (e.g. "veo3_1_lite") if the channel.yml happens to use them.
 */
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final BibleLoader bible;

    /** Pick with an optional per-scene model override (UI dropdown). When set and
     *  not cost-capped, it wins over the bible routing; resolution follows the
     *  chosen model's tier. Blank → normal {@link #pick(SceneType,int,boolean)}. */
    public ModelRoute pick(SceneType type, int requestedDuration, boolean costCapNearby, String modelOverride) {
        if (modelOverride != null && !modelOverride.isBlank() && !costCapNearby) {
            String modelId = normaliseModelId(modelOverride.trim());
            int maxDur = bible.getVideoGen().veo().maxClipSeconds();
            int dur = snapDuration(modelId, Math.min(requestedDuration, maxDur));
            String resolution = isHighTier(modelId, false) ? "1080p" : "720p";
            return new ModelRoute(modelId, resolution, dur);
        }
        return pick(type, requestedDuration, costCapNearby);
    }

    public ModelRoute pick(SceneType type, int requestedDuration, boolean costCapNearby) {
        VideoGenConfig.Veo veo = bible.getVideoGen().veo();

        String modelId;
        int maxDur;

        if (costCapNearby) {
            // Force the cheapest path once we're approaching the cap.
            modelId = veo.defaultModel();
            maxDur = veo.maxClipSeconds();
        } else {
            VideoGenConfig.Routing route = veo.routing().stream()
                    .filter(r -> r.sceneType().equalsIgnoreCase(type.wireName()))
                    .findFirst()
                    .orElse(null);
            if (route != null) {
                modelId = route.model() != null ? route.model() : veo.defaultModel();
                maxDur  = route.maxSeconds() != null ? route.maxSeconds() : veo.maxClipSeconds();
            } else {
                modelId = veo.defaultModel();
                maxDur  = veo.maxClipSeconds();
            }
        }

        modelId = normaliseModelId(modelId);
        int dur = snapDuration(modelId, Math.min(requestedDuration, maxDur));
        String resolution = isHighTier(modelId, costCapNearby) ? "1080p" : "720p";
        return new ModelRoute(modelId, resolution, dur);
    }

    /**
     * Veo image-to-video only accepts durations {4, 6, 8} — a scripted 3s scene
     * passed through verbatim fails the whole clip with
     * "Unsupported output video duration 3 seconds" (seen live on ep-job
     * e178e7d9, scene 8). Snap UP to the nearest supported value: a slightly
     * longer clip is harmless (assembly trims to the scene length), a refused
     * clip is a Ken Burns fallback. Seedance (fal.ai) accepts arbitrary
     * durations, so non-Veo ids pass through unchanged.
     */
    private int snapDuration(String modelId, int dur) {
        if (!modelId.startsWith("veo")) return dur;
        if (dur <= 4) return 4;
        if (dur <= 6) return 6;
        return 8;
    }

    /** The cheapest real-motion route (Veo Lite, 720p). Used by the cost-cap
     *  downshift: a moving lite clip (€0.05/s) beats a frozen Ken Burns still
     *  every time — the still/motion mix was the most visible AI-tell in the
     *  ep-2 audit. */
    public ModelRoute cheapest(int requestedDuration) {
        int dur = snapDuration("veo",
                Math.min(requestedDuration, bible.getVideoGen().veo().maxClipSeconds()));
        return new ModelRoute("veo-3.1-lite-generate-preview", "720p", dur);
    }

    public ModelRoute fallback(int requestedDuration) {
        VideoGenConfig.Veo veo = bible.getVideoGen().veo();
        String modelId = normaliseModelId(veo.fallbackModel());
        int dur = snapDuration(modelId, Math.min(requestedDuration, veo.maxClipSeconds()));
        return new ModelRoute(modelId, "720p", dur);
    }

    private boolean isHighTier(String modelId, boolean costCapNearby) {
        if (costCapNearby) return false;
        // Lite is the cost-optimised tier → keep it at 720p. Fast + Standard
        // get the 1080p high tier.
        if (modelId.contains("lite")) return false;
        if (modelId.contains("veo-3.1-generate")) return true;   // full Veo 3.1 = 1080p
        // Seedance 2.0 standard supports 1080p via the fal API; fast stays 720p.
        return modelId.startsWith("bytedance/") && !modelId.contains("/fast/");
    }

    /**
     * Maps friendly bible aliases to the OFFICIAL Vertex AI model ids.
     *   veo3_1_fast → veo-3.1-fast-generate-001       (GA workhorse, 720p)
     *   veo3_1      → veo-3.1-generate-001            (GA premium, 1080p)
     *   veo3_1_lite → veo-3.1-lite-generate-preview   (preview; cheapest, 720p)
     *   veo3        → veo-3.0-fast-generate-001       (legacy GA — retired ~2026-06-30)
     * Veo 3.1 Fast/Standard are now GA ("-001", no allowlist); they replace the
     * Veo 3.0 endpoints, which are being deprecated. Lite is still preview.
     */
    private String normaliseModelId(String raw) {
        if (raw == null) return "veo-3.1-fast-generate-001";
        String s = raw.trim();
        return switch (s) {
            case "veo3_1_lite"        -> "veo-3.1-lite-generate-preview";
            case "veo3_1_fast"        -> "veo-3.1-fast-generate-001";
            case "veo3_1"             -> "veo-3.1-generate-001";
            case "veo3", "veo3_fast"  -> "veo-3.0-fast-generate-001";
            // Second provider: Seedance 2.0 via fal.ai (ids start with
            // "bytedance/" — ClipGenerationService routes those to the
            // FalSeedanceClient instead of Vertex Veo).
            case "seedance2", "seedance" -> "bytedance/seedance-2.0/image-to-video";
            case "seedance2_fast"        -> "bytedance/seedance-2.0/fast/image-to-video";
            default                   -> s; // assume caller already used a Vertex id
        };
    }
}
