package com.youtubeauto.voice.elevenlabs;

import java.util.List;

/**
 * Pure selection logic for the ElevenLabs prosody-context fields
 * ({@code previous_text} / {@code next_text} in the TTS request body).
 *
 * Why per speaker: ElevenLabs uses the context to continue the prosody of ONE
 * voice. Feeding it another character's line as "what was just said" confuses
 * the model — so for line {@code i} of speaker X we pick only X's own nearest
 * lines as context.
 *
 * Window: the same scene plus the directly adjacent scene (the scene before
 * for {@code previous_text}, the scene after for {@code next_text}). Anything
 * further away is a different beat and no longer one continuous performance.
 *
 * Cap: each side is capped at {@link #MAX_CHARS} characters — well under the
 * API limit. {@code previous_text} keeps its TAIL (the words leading into the
 * current line), {@code next_text} keeps its HEAD (the words following it).
 *
 * No candidate → {@code null}, and the client then OMITS the field entirely
 * (it never sends an empty string).
 */
public final class ProsodyContext {

    /** Per-side character cap — kept well under the ElevenLabs API limit. */
    public static final int MAX_CHARS = 300;

    private ProsodyContext() {}

    /** One dialogue line in episode order. */
    public record Entry(int sceneSeq, String speaker, String text) {}

    /** The previous/next context pair for one line; either side may be null. */
    public record Context(String previousText, String nextText) {}

    /** Determines the previous/next same-speaker context for {@code lines.get(idx)}. */
    public static Context contextFor(List<Entry> lines, int idx) {
        return new Context(previousFor(lines, idx), nextFor(lines, idx));
    }

    /** Nearest earlier same-speaker line within the window, or null. */
    public static String previousFor(List<Entry> lines, int idx) {
        return neighbor(lines, idx, -1);
    }

    /** Nearest later same-speaker line within the window, or null. */
    public static String nextFor(List<Entry> lines, int idx) {
        return neighbor(lines, idx, +1);
    }

    private static String neighbor(List<Entry> lines, int idx, int dir) {
        if (lines == null || idx < 0 || idx >= lines.size()) return null;
        Entry cur = lines.get(idx);
        if (cur.speaker() == null) return null;
        for (int i = idx + dir; i >= 0 && i < lines.size(); i += dir) {
            Entry cand = lines.get(i);
            // Entries are in episode order, so once a candidate falls outside
            // the window (same scene + adjacent scene) every further one does too.
            if (Math.abs(cand.sceneSeq() - cur.sceneSeq()) > 1) return null;
            if (cur.speaker().equalsIgnoreCase(cand.speaker())
                    && cand.text() != null && !cand.text().isBlank()) {
                return cap(cand.text().trim(), dir);
            }
        }
        return null;
    }

    /** previous_text keeps the tail (most recent words), next_text the head. */
    private static String cap(String text, int dir) {
        if (text.length() <= MAX_CHARS) return text;
        return dir < 0
                ? text.substring(text.length() - MAX_CHARS)
                : text.substring(0, MAX_CHARS);
    }
}
