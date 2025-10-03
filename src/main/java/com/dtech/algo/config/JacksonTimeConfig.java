package com.dtech.algo.config;

import com.dtech.algo.jackson.InstantAppZoneSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

@Configuration
public class JacksonTimeConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer timeZoneAwareDates() {
        return builder -> {
            JavaTimeModule javaTimeModule = new JavaTimeModule();
            // Render Instants in application time-zone
            javaTimeModule.addSerializer(Instant.class, new InstantAppZoneSerializer());
            builder.modules(javaTimeModule);
            builder.simpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
            builder.timeZone(java.util.TimeZone.getDefault()); // Jackson legacy formats if any
        };
    }
}
