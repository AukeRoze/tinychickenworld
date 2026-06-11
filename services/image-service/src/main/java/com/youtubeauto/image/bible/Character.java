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
        /** Canonical iconic identity — the "character DNA" injected into every
         *  prompt so identity is afdwingbaar, not hoped-for. Never null
         *  (BibleLoader supplies an empty Dna when the bible omits it). */
        Dna dna
) {
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
            String antiAccessory     // accessories this character must NEVER wear (anti-swap)
    ) {
        public static Dna empty() { return new Dna("", "", "", "", "", "", "", "", "", ""); }

        public boolean hasAccessory() { return notBlank(accessory); }
        public boolean hasSilhouette() { return notBlank(silhouette); }
        public boolean hasTic() { return notBlank(tic); }
        public boolean hasCoreColor() { return notBlank(coreColor); }
        public boolean hasFeathers() { return notBlank(feathers); }
        public boolean hasBuild() { return notBlank(build); }
        public boolean hasWeight() { return notBlank(weight); }
        public boolean hasEyeColor() { return notBlank(eyeColor); }
        public boolean hasAntiAccessory() { return notBlank(antiAccessory); }

        private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    }
}
