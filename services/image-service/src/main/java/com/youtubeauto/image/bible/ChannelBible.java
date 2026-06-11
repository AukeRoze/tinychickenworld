package com.youtubeauto.image.bible;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public record ChannelBible(
        String visualStyle,
        List<Character> characters,
        List<Location> locations,
        ImageGenConfig imageGen,
        String worldOverview,
        List<WorldMood> timeOfDay,
        List<WorldMood> weather
) {
    public ChannelBible(String visualStyle, List<Character> characters,
                        List<Location> locations, ImageGenConfig imageGen) {
        this(visualStyle, characters, locations, imageGen, "", List.of(), List.of());
    }

    private static Map<String, Character> charById(List<Character> chars) {
        return chars.stream().collect(Collectors.toMap(Character::id, c -> c));
    }
    private static Map<String, Location> locById(List<Location> locs) {
        return locs.stream().collect(Collectors.toMap(Location::id, l -> l));
    }

    public Optional<Character> character(String id) {
        return Optional.ofNullable(charById(characters).get(id));
    }
    public Optional<Location> location(String id) {
        return Optional.ofNullable(locById(locations).get(id));
    }
    public Optional<WorldMood> timeOfDay(String id) {
        if (timeOfDay == null || id == null) return Optional.empty();
        return timeOfDay.stream().filter(t -> id.equalsIgnoreCase(t.id())).findFirst();
    }
    public Optional<WorldMood> weather(String id) {
        if (weather == null || id == null) return Optional.empty();
        return weather.stream().filter(w -> id.equalsIgnoreCase(w.id())).findFirst();
    }

    /** Generic mood record used for both time-of-day and weather. */
    public record WorldMood(String id, String label, String description) {}
}
