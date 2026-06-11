package com.youtubeauto.script.bible;

import java.util.List;

public record BibleCharacter(
        String id,
        String name,
        String role,            // main | sidekick
        String personality,
        List<String> openers,
        List<String> closers
) {}
