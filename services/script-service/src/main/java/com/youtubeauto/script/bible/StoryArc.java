package com.youtubeauto.script.bible;

import java.util.List;

/** One narrative structure that Claude must follow when writing a script. */
public record StoryArc(String id, String label, List<String> beats) {}
