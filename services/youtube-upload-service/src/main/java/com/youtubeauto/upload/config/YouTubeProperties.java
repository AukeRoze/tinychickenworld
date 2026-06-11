package com.youtubeauto.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.youtube")
public record YouTubeProperties(
        String applicationName,
        String clientSecretsPath,
        String credentialsDir,
        String defaultCategoryId,
        String defaultPrivacy,
        List<String> defaultTags,
        boolean madeForKids,
        /** Sets {@code status.containsSyntheticMedia} on every upload.
         *  Required by YouTube policy when realistic A/S content is used
         *  (deepfakes, voice-clones of real people, fake "real" scenes).
         *  For clearly-animated cartoons it's not technically required,
         *  but enabling it is the safe default — over-disclosing has
         *  negligible algorithmic cost; under-disclosing risks strikes. */
        boolean containsSyntheticMedia
) {}
