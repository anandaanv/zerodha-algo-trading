package com.dtech.kitecon.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Scheduler that rotates service tokens daily.
 * Runs at midnight every day and on application startup.
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class ServiceTokenScheduler {

    private final ServiceTokenService serviceTokenService;

    /**
     * Initialize service token on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application ready - ensuring service token exists for today");
        try {
            String token = serviceTokenService.getCurrentToken();
            log.info("Service token initialized successfully for date: {}", LocalDate.now());
        } catch (Exception e) {
            log.error("Failed to initialize service token on startup", e);
        }
    }

    /**
     * Rotate service token daily at midnight.
     * Cron: second minute hour day month weekday
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void rotateDailyToken() {
        log.info("Starting daily service token rotation");
        try {
            String newToken = serviceTokenService.rotateToken();
            log.info("Daily service token rotated successfully. New token generated for: {}", LocalDate.now());
        } catch (Exception e) {
            log.error("Failed to rotate daily service token", e);
        }
    }
}
