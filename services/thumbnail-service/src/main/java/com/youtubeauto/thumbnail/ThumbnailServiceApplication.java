package com.youtubeauto.thumbnail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ThumbnailServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ThumbnailServiceApplication.class, args);
    }
}
