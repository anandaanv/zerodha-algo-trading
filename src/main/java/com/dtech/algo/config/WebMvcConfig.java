package com.dtech.algo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Web MVC configuration for serving static resources
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${charts.output.directory:./charts}")
    private String chartsOutputDirectory;

    @Value("${charts.temp.directory:./charts/temp}")
    private String chartsTempDirectory;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Configure handlers for serving chart images from file system
        registry.addResourceHandler("/charts/**")
                .addResourceLocations("file:" + Paths.get(chartsOutputDirectory).toAbsolutePath().toString() + "/");

        registry.addResourceHandler("/charts/temp/**")
                .addResourceLocations("file:" + Paths.get(chartsTempDirectory).toAbsolutePath().toString() + "/")
                .setCachePeriod(3600); // Cache for 1 hour
    }
}
