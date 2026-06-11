package com.youtubeauto.script.bible;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public record ChannelBible(
        String channelName,
        String audience,
        List<BibleCharacter> characters,
        List<BibleLocation> locations,
        List<StoryArc> storyArcs,
        EpisodeStructure episodeStructure,
        /** Editable persona injected at the TOP of the script-generation system
         *  prompt (bible personas.storyWriter). Lets you tune the writer's voice
         *  without a rebuild. Empty = use the built-in head-writer framing only. */
        String storyWriterPersona,
        /** Editable persona injected into the dialogue punch-up pass
         *  (bible personas.humorSpecialist). Empty = built-in comedy-doctor only. */
        String humorSpecialistPersona
) {
    public Optional<BibleCharacter> mainCharacter() {
        return characters.stream().filter(c -> "main".equalsIgnoreCase(c.role())).findFirst();
    }
    public List<String> characterIds() {
        return characters.stream().map(BibleCharacter::id).toList();
    }
    public List<String> locationIds() {
        return locations.stream().map(BibleLocation::id).toList();
    }
    /** Random story arc — used per script so episodes feel structurally distinct. */
    public Optional<StoryArc> randomStoryArc() {
        if (storyArcs == null || storyArcs.isEmpty()) return Optional.empty();
        return Optional.of(storyArcs.get(ThreadLocalRandom.current().nextInt(storyArcs.size())));
    }

    /** Lookup by arc id — used when the orchestrator's performance-weighted
     *  selector dictates the arc instead of the legacy random pick. */
    public Optional<StoryArc> storyArc(String id) {
        if (id == null || id.isBlank() || storyArcs == null) return Optional.empty();
        return storyArcs.stream().filter(a -> id.equalsIgnoreCase(a.id())).findFirst();
    }
}
