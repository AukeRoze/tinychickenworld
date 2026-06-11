package com.youtubeauto.voice.service;

import com.youtubeauto.voice.api.dto.SynthesizeRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests emotion-detection heuristics in SfxComposer.chooseEmotion(...).
 * The method is private; reached via reflection to avoid exposing it.
 */
class SfxComposerEmotionTest {

    private final SfxComposer composer = new SfxComposer(null, null);

    @Test
    void explicitEmotionTagWinsOverText() throws Exception {
        var line = new SynthesizeRequest.Line("pip", "anything here", "laughing");
        assertEquals("laughing", invokeChooseEmotion(line, "content"));
    }

    @Test
    void gaspKeywordMapsToGasping() throws Exception {
        var line = new SynthesizeRequest.Line("pip", "Pip gasps at the sight of the sunrise.", null);
        assertEquals("gasping", invokeChooseEmotion(line, "content"));
    }

    @Test
    void laughKeywordMapsToLaughing() throws Exception {
        var line = new SynthesizeRequest.Line("bo", "Bo giggles and falls over.", null);
        assertEquals("laughing", invokeChooseEmotion(line, "content"));
    }

    @Test
    void exclamationMapsToSurprised() throws Exception {
        var line = new SynthesizeRequest.Line("pip", "Oh! Mo, look!", null);
        assertEquals("surprised", invokeChooseEmotion(line, "content"));
    }

    @Test
    void thinkingKeywordMapsToThinking() throws Exception {
        var line = new SynthesizeRequest.Line("mo", "Hmm, that's curious.", null);
        assertEquals("thinking", invokeChooseEmotion(line, "content"));
    }

    @Test
    void unrelatedTextFallsBackToContent() throws Exception {
        var line = new SynthesizeRequest.Line("pip", "Just walking along.", null);
        assertEquals("content", invokeChooseEmotion(line, "content"));
    }

    @Test
    void explicitEmotionIsLowercased() throws Exception {
        var line = new SynthesizeRequest.Line("pip", "anything", "LAUGHING");
        assertEquals("laughing", invokeChooseEmotion(line, "content"));
    }

    private String invokeChooseEmotion(SynthesizeRequest.Line line, String fallback)
            throws Exception {
        Method m = SfxComposer.class.getDeclaredMethod(
                "chooseEmotion", SynthesizeRequest.Line.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(composer, line, fallback);
    }
}
