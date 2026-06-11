package com.youtubeauto.script.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.dedupe")
public record DedupeProperties(
        /** Similarity above which a script is rejected. 0.80 = block if &gt;80% similar. */
        double similarityThreshold,
        /** How many recent scripts to scan for near-duplicates. */
        int lookbackWindow,
        /** Max regenerations on duplicate hit before failing the job. */
        int maxRetries
) {}
