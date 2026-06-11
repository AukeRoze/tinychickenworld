package com.youtubeauto.upload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling   // OAuth-token health probe (OAuthHealthProbe)
public class YouTubeUploadApplication {
    public static void main(String[] args) { SpringApplication.run(YouTubeUploadApplication.class, args); }
}
