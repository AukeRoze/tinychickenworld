package com.youtubeauto.videogen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gcp")
public record GcpProperties(
        String projectId,
        String region,
        String credentialsPath,
        String outputBucket,
        String outputPrefix
) {}
