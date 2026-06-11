package com.youtubeauto.orchestrator.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic metadata gate — runs AFTER the LLM so brand rules are
 * GUARANTEED instead of prompted. Born from the ep-2 and ep-3 audits, which
 * both flagged the same two issues that the prompt alone kept reproducing:
 *
 *   1. #BedtimeStories in the description — a category mismatch that teaches
 *      the algorithm the wrong audience slot (the old prompt literally listed
 *      the tag; fixed there too, but a prompt is a request, this is a rule).
 *   2. No series branding — viewers and the algorithm never see
 *      "Episode N of Tiny Chicken World" anywhere in the description.
 *
 * Every change is recorded as a human-readable "fix" so the dashboard
 * (QC-patterns) shows what the policy rewrote, keeping the human in the loop.
 */
@Slf4j
@Component
public class MetadataPolicy {

    /** Hashtags that must never ship (lowercase, no '#'). */
    private static final List<String> BANNED_HASHTAGS =
            List.of("bedtimestories", "bedtimestory");

    /** Plain tags that must never ship (lowercase). */
    private static final List<String> BANNED_TAGS =
            List.of("bedtime story", "bedtime stories");

    /** Hashtags every description must carry (canonical casing). */
    private static final List<String> REQUIRED_HASHTAGS =
            List.of("#TinyChickenWorld", "#KidsCartoon", "#ToddlerLearning");

    private static final String SERIES_NAME = "Tiny Chicken World";

    private static final Pattern EPISODE_LINE = Pattern.compile(
            "(?i)episode\\s+\\d+\\s+of\\s+" + Pattern.quote(SERIES_NAME));

    public record Result(MetadataGenerator.Metadata metadata, List<String> fixes) {}

    public Result apply(MetadataGenerator.Metadata in, Integer episodeNumber) {
        List<String> fixes = new ArrayList<>();
        String desc = in.description() == null ? "" : in.description();

        // 1. Strip banned hashtags from the description.
        for (String banned : BANNED_HASHTAGS) {
            Pattern p = Pattern.compile("(?i)#" + banned + "\\b\\s*");
            Matcher m = p.matcher(desc);
            if (m.find()) {
                desc = m.replaceAll("").replaceAll("[ \\t]{2,}", " ");
                fixes.add("removed banned hashtag #" + banned);
            }
        }

        // 2. Ensure the required hashtags are present — appended to the end
        //    (where the hashtag block lives) when missing.
        List<String> missing = new ArrayList<>();
        for (String req : REQUIRED_HASHTAGS) {
            if (!desc.toLowerCase().contains(req.toLowerCase())) missing.add(req);
        }
        if (!missing.isEmpty()) {
            desc = desc.stripTrailing() + (desc.endsWith("#") ? " " : "\n")
                    + String.join(" ", missing) + "\n";
            fixes.add("appended required hashtag(s): " + String.join(" ", missing));
        }

        // 3. Series/episode branding. With a known episode number the FIRST
        //    line becomes "🐤 Episode N of Tiny Chicken World"; without one we
        //    still guarantee the series name appears somewhere.
        if (episodeNumber != null && episodeNumber > 0) {
            if (!EPISODE_LINE.matcher(desc).find()) {
                desc = "🐤 Episode " + episodeNumber + " of " + SERIES_NAME + "\n\n" + desc;
                fixes.add("prepended series line: Episode " + episodeNumber
                        + " of " + SERIES_NAME);
            }
        } else if (!desc.toLowerCase().contains(SERIES_NAME.toLowerCase())) {
            desc = desc.stripTrailing() + "\n\nPart of the " + SERIES_NAME + " series.\n";
            fixes.add("appended series-name line (no episode number on the job)");
        }

        // 4. Tags: drop banned ones, guarantee the brand tag.
        List<String> tags = new ArrayList<>(in.tags() == null ? List.of() : in.tags());
        boolean removed = tags.removeIf(t ->
                t != null && BANNED_TAGS.contains(t.trim().toLowerCase()));
        if (removed) fixes.add("removed banned tag(s): bedtime story/stories");
        boolean hasBrand = tags.stream().anyMatch(t ->
                t != null && t.trim().equalsIgnoreCase("tiny chicken world"));
        if (!hasBrand) {
            tags.add(0, "tiny chicken world");
            fixes.add("added brand tag 'tiny chicken world'");
        }

        if (!fixes.isEmpty()) {
            log.info("Metadata policy applied {} fix(es): {}", fixes.size(), fixes);
        }
        return new Result(
                new MetadataGenerator.Metadata(in.title(), desc, tags), fixes);
    }
}
