package com.youtubeauto.image.bible;

public record Character(
        String id,
        String name,
        String description,
        /** LoRA trigger token used when imageGen.provider=replicate. */
        String triggerWord,
        /** Series-wide age/stage. Same for all scenes in one video; you edit
         *  the bible to age the cast across episodes. */
        String lifeStage,
        /** Biological species, bible {@code characters[].species} (e.g. "chicken",
         *  "duck"). Used to count a mixed cast correctly. May be "". */
        String species,
        /** The word used in the roster COUNT, bible {@code characters[].rosterNoun}
         *  (e.g. "duckling"); falls back to species, then "chicken". May be "". */
        String rosterNoun,
        /** Canonical iconic identity — the "character DNA" injected into every
         *  prompt so identity is afdwingbaar, not hoped-for. Never null
         *  (BibleLoader supplies an empty Dna when the bible omits it). */
        Dna dna
) {
    /** Back-compat 6-arg form (no species/rosterNoun) → both default to "". */
    public Character(String id, String name, String description, String triggerWord,
                     String lifeStage, Dna dna) {
        this(id, name, description, triggerWord, lifeStage, "", "", dna);
    }

    /** The display noun for the roster count: rosterNoun, else species, else
     *  "chicken" — so the duckling never gets counted as a chicken. */
    public String displayNoun() {
        if (rosterNoun != null && !rosterNoun.isBlank()) return rosterNoun.trim().toLowerCase();
        if (species != null && !species.isBlank()) return species.trim().toLowerCase();
        return "chicken";
    }
    /**
     * The unmistakable, repeated identity of a character. Lives in the bible
     * (characters[].dna) so it is the single source of truth across image, Veo
     * and thumbnail prompts.
     */
    public record Dna(
            String coreColor,        // one-word body colour, e.g. "cream-white"
            String silhouette,       // instantly-recognisable shape
            String accessory,        // must-have accessory (the hard lock)
            String tic,              // signature repeated behaviour (pose/motion hint)
            String signatureSound,   // short audio cue (used by sfx/voice layer)
            // Extended cross-shot identity — the fine details a model drops
            // between shots when there is no identical reference still. Driven
            // entirely by the bible (characters[].dna); never hardcoded.
            String feathers,         // feather texture / colour detail
            String build,            // body proportions / shape
            String weight,           // felt weight + how it moves (Veo motion hint)
            String eyeColor,         // iris colour + highlight description
            String antiAccessory,    // accessories this character must NEVER wear (anti-swap)
            String signatureAccessoryShort // short noun for THIS character's own accessory
                                           // (the accessory-guard rewrite target)
    ) {
        public static Dna empty() { return new Dna("", "", "", "", "", "", "", "", "", "", ""); }

        public boolean hasAccessory() { return notBlank(accessory); }
        public boolean hasSilhouette() { return notBlank(silhouette); }
        public boolean hasTic() { return notBlank(tic); }
        public boolean hasCoreColor() { return notBlank(coreColor); }
        public boolean hasFeathers() { return notBlank(feathers); }
        public boolean hasBuild() { return notBlank(build); }
        public boolean hasWeight() { return notBlank(weight); }
        public boolean hasEyeColor() { return notBlank(eyeColor); }
        public boolean hasAntiAccessory() { return notBlank(antiAccessory); }
        public boolean hasSignatureAccessoryShort() { return notBlank(signatureAccessoryShort); }

        private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    }
}
