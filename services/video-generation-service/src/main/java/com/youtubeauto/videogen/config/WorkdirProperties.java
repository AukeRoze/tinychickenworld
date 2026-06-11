package com.youtubeauto.videogen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.workdir")
public record WorkdirProperties(String root) {}
