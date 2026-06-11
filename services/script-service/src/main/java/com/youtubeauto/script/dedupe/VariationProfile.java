package com.youtubeauto.script.dedupe;

/**
 * Four orthogonal randomization axes injected into the prompt. Combining
 * these forces the LLM to produce structurally and tonally distinct scripts
 * even when the topic is similar to previous ones — which is the single
 * biggest signal YouTube uses to flag "AI farm" channels.
 */
public record VariationProfile(Hook hook, Tone tone, Structure structure, ExampleStyle examples) {

    public enum Hook {
        QUESTION("Open with an intriguing question the viewer wants answered."),
        SURPRISING_FACT("Open with a surprising factual statement, no preamble."),
        MINI_STORY("Open with a one-line story or scene ('Once, a little fox...')."),
        DIRECT_ADDRESS("Open by talking directly to the viewer ('Hey explorer!')."),
        SOUND_WORD("Open with an onomatopoeic word or playful sound ('Whoosh! Zoom!').");
        public final String instruction;
        Hook(String s) { this.instruction = s; }
    }

    public enum Tone {
        ENTHUSIASTIC("Energetic and excited — short bursts, exclamations."),
        CALM("Calm and soothing — measured pacing, gentle words."),
        PLAYFUL("Silly and playful — light jokes, rhymes."),
        CURIOUS("Curious and wondering — frequent 'I wonder', 'what if'."),
        DRAMATIC_PAUSE("Use deliberate pauses (written as '...') for suspense.");
        public final String instruction;
        Tone(String s) { this.instruction = s; }
    }

    public enum Structure {
        LINEAR("Linear narrative: each scene builds on the previous in time order."),
        PROBLEM_SOLUTION("Set up a small problem in scene 1, resolve it in the final scene."),
        COMPARE_CONTRAST("Compare two things scene by scene (A vs B, A vs B, ...)."),
        LISTICLE("Number the scenes ('First...', 'Second...', 'Third...')."),
        JOURNEY("Frame as a journey: depart, encounter, return changed.");
        public final String instruction;
        Structure(String s) { this.instruction = s; }
    }

    public enum ExampleStyle {
        REAL_WORLD("Use concrete real-world examples a child would recognise."),
        IMAGINATIVE("Use imaginative or fantastical examples (talking animals, magic)."),
        HISTORICAL("Use simple historical or 'long ago' framing."),
        EVERYDAY("Use everyday-life examples (kitchen, park, school).");
        public final String instruction;
        ExampleStyle(String s) { this.instruction = s; }
    }

    /** Serialise to the form stored in scripts.variation_profile for auditing. */
    public String tag() {
        return "hook=" + hook + ";tone=" + tone + ";structure=" + structure + ";examples=" + examples;
    }
}
