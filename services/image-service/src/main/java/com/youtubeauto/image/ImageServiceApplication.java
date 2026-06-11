package com.youtubeauto.image;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ImageServiceApplication {
    public static void main(String[] args) { SpringApplication.run(ImageServiceApplication.class, args); }
}
