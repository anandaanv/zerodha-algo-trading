package com.dtech.algo.config;

import com.dtech.algo.time.ZoneIdHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

/**
 * Initializes the application-wide ZoneId.
 * Property: app.time-zone (default: UTC)
 */
@Configuration
@Slf4j
public class TimeZoneConfig {

    @Value("${app.time-zone:UTC}")
    private String appTimeZone;

    @PostConstruct
    public void init() {
        try {
            ZoneId zone = ZoneId.of(appTimeZone);
            ZoneIdHolder.set(zone);
            log.info("Application time-zone set to {}", zone);
        } catch (Exception e) {
            log.warn("Invalid app.time-zone '{}', falling back to UTC. Reason: {}", appTimeZone, e.getMessage());
        }
    }
}
