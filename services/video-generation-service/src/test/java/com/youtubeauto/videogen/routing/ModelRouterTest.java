package com.youtubeauto.videogen.routing;

import com.youtubeauto.videogen.bible.BibleLoader;
import com.youtubeauto.videogen.bible.VideoGenConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P7 — guards the per-scene Veo model routing: bible-driven model selection,
 * cost-cap downshift, hero→1080p tiering and friendly-alias → Vertex-id
 * normalisation. These are the rules that silently control how much each scene
 * costs, so a regression here is expensive and invisible without a test.
 */
class ModelRouterTest {

    private ModelRouter routerWith(VideoGenConfig cfg) {
        BibleLoader bible = mock(BibleLoader.class);
        when(bible.getVideoGen()).thenReturn(cfg);
        return new ModelRouter(bible);
    }

    @Test
    void heroScene_getsHeroModelAt1080p() {
        ModelRouter router = routerWith(VideoGenConfig.defaults());
        ModelRoute r = router.pick(SceneType.HERO, 8, false);
        assertEquals("veo-3.1-generate-preview", r.modelId());
        assertEquals("1080p", r.resolution());
        assertEquals(8, r.durationSec());
    }

    @Test
    void standardScene_staysOnLiteAt720p_andClampsDuration() {
        ModelRouter router = routerWith(VideoGenConfig.defaults());
        ModelRoute r = router.pick(SceneType.STANDARD, 8, false);
        assertEquals("veo-3.1-fast-generate-preview", r.modelId());
        assertEquals("720p", r.resolution());
        assertEquals(6, r.durationSec());   // clamped to the standard route's maxSeconds (6)
    }

    @Test
    void costCapNearby_forcesDefaultModelAnd720p() {
        ModelRouter router = routerWith(VideoGenConfig.defaults());
        ModelRoute r = router.pick(SceneType.HERO, 8, true);   // costCapNearby = true
        assertEquals("veo-3.1-fast-generate-preview", r.modelId());  // downshift to defaultModel
        assertEquals("720p", r.resolution());                        // never the 1080p high tier
        assertEquals(6, r.durationSec());
    }

    @Test
    void fallback_usesFallbackModelAt720p() {
        ModelRouter router = routerWith(VideoGenConfig.defaults());
        ModelRoute r = router.fallback(8);
        assertEquals("veo-3.0-fast-generate-001", r.modelId());
        assertEquals("720p", r.resolution());
        assertEquals(6, r.durationSec());
    }

    @Test
    void shortScene_snapsUpToVeoSupportedDuration() {
        // Veo image-to-video only accepts {4,6,8}; a scripted 3s scene failed
        // live with "Unsupported output video duration 3 seconds". Snap up.
        ModelRouter router = routerWith(VideoGenConfig.defaults());
        assertEquals(4, router.pick(SceneType.STANDARD, 3, false).durationSec());
        assertEquals(6, router.pick(SceneType.STANDARD, 5, false).durationSec());
        assertEquals(4, router.cheapest(2).durationSec());
        assertEquals(4, router.fallback(3).durationSec());
    }

    @Test
    void friendlyAliases_areNormalisedToVertexIds() {
        // A channel.yml using the friendly underscore aliases must still resolve
        // to official Vertex model ids.
        VideoGenConfig cfg = new VideoGenConfig("veo",
                new VideoGenConfig.Veo(
                        "veo3_1_lite",   // defaultModel alias
                        "veo3_1",        // heroModel alias (informational)
                        "high",
                        "veo3",          // fallback alias
                        8, false, 5.0,
                        List.of(new VideoGenConfig.Routing("hero", "veo3_1", 8))));
        ModelRouter router = routerWith(cfg);

        assertEquals("veo-3.1-generate-preview",
                router.pick(SceneType.HERO, 8, false).modelId());
        assertEquals("veo-3.1-lite-generate-preview",
                router.pick(SceneType.STANDARD, 8, false).modelId());  // no standard route → defaultModel alias
        assertEquals("veo-3.0-fast-generate-001",
                router.fallback(8).modelId());
    }
}
