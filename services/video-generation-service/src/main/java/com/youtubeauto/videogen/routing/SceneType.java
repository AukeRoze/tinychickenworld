package com.youtubeauto.videogen.routing;

public enum SceneType {
    INTRO, OUTRO, HERO, STANDARD;

    public static SceneType parse(String s) {
        if (s == null) return STANDARD;
        return switch (s.toLowerCase()) {
            case "intro" -> INTRO;
            case "outro" -> OUTRO;
            case "hero"  -> HERO;
            default      -> STANDARD;
        };
    }

    public String wireName() {
        return name().toLowerCase();
    }
}
