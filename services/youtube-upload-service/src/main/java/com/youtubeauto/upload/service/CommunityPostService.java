package com.youtubeauto.upload.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Auto-generates community-tab posts for the channel.
 *
 * IMPORTANT: as of 2025 YouTube's Data API does NOT expose Community Tab
 * post creation programmatically — these posts have to be created via
 * YouTube Studio manually or via the unofficial mobile-API reverse-engineered
 * libraries (unsupported, risky).
 *
 * This service produces READY-TO-PASTE TEXT for the user to drop into
 * the Community Tab manually. Saves time and keeps cadence even if YT
 * never opens up the API.
 *
 * Templates are aligned with proven kids-channel patterns: sneak peek,
 * behind-the-scenes, poll, character spotlight, "what next?".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityPostService {

    public Map<String, Object> generatePostIdeas(String latestVideoTopic,
                                                  String nextVideoTopic) {
        String[] templates = {
            "🐤 Sneak peek of tomorrow's episode!\n\nWhat do you think Pip discovers next?\n#TinyChickenWorld #KidsCartoon",
            "🌅 Behind the scenes from \"%s\":\n\nFun fact — Mo's straw hat was specifically designed to catch the golden hour light. Did you spot it?\n\n#BTS #TinyChickenWorld",
            "📊 POLL: What's YOUR favorite chick?\n\n🐤 Pip — the curious blue-grey one\n🐔 Mo — the calm chick with the straw hat\n🐣 Bo — the playful one with white glasses\n\nVote below! ⬇️",
            "✨ Just dropped: \"%s\" — perfect for cozy quiet time. Watch it now!\n\nNew episode every Tuesday at 10:00 CET!",
            "💛 We love seeing your kids enjoy the show. Reply with a 💛 if you watched today's episode! Means the world to us.",
            "🎨 Concept art preview!\n\nHere's a sneak peek at the location we're visiting next week — can you guess what adventure awaits?\n\n#ConceptArt #ComingSoon",
            "🔍 Tiny detail in this week's episode:\n\nDid you notice the decorated egg in the background of every scene? Pip painted it! 🎨\n\n#EasterEggs #TinyChickenWorld",
            "💭 Quote of the day:\n\n\"Mornings are best when you share them.\" — Mo\n\nWhat's YOUR favorite morning ritual?",
            "🌟 1 week of Tiny Chicken World! Thank you all for the warm welcome — Pip, Mo and Bo say peep-peep!\n\n#GrowingTogether #KidsAnimation",
            "👋 Question for parents: what kind of stories would you love to see our chicks tackle next? Drop your ideas below — we read every one!"
        };
        int n = templates.length;
        // Auto-fills %%s when present and the user provides values.
        var posts = java.util.stream.IntStream.range(0, Math.min(5, n))
                .mapToObj(i -> {
                    String raw = templates[(i * 3) % n];
                    String filled = raw
                            .replace("%s", i % 2 == 0
                                    ? (latestVideoTopic == null ? "(latest topic)" : latestVideoTopic)
                                    : (nextVideoTopic == null ? "(next topic)" : nextVideoTopic));
                    return Map.of("body", filled,
                            "scheduleHint", "Post " + (i + 1) + " — schedule "
                                    + (i + 1) + " day(s) after upload");
                }).toList();

        return Map.of(
                "posts", posts,
                "instructions", List.of(
                        "YouTube Data API doesn't support Community Tab posts (Studio-only).",
                        "Copy the post body and paste into YouTube Studio → Community → New post.",
                        "Schedule for the suggested day to maintain algorithm-friendly cadence."
                ),
                "cadenceTip", "Post every 1-3 days between video uploads. Steady community activity is what keeps your channel in the algorithm's good graces."
        );
    }
}
