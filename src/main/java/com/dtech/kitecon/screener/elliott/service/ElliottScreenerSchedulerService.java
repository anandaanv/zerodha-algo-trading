package com.dtech.kitecon.screener.elliott.service;

import com.dtech.kitecon.screener.elliott.entity.ElliottScreener;
import com.dtech.kitecon.screener.elliott.repository.ElliottScreenerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ElliottScreenerSchedulerService {

    private final ElliottScreenerRepository screenerRepository;
    private final ElliottScreenerService screenerService;

    @Scheduled(cron = "${elliott.screener.scheduler.cron:0 0 * * * ?}")
    public void tick() {
        List<ElliottScreener> screeners = screenerRepository.findByEnabledTrue();
        if (screeners.isEmpty()) return;

        ZonedDateTime now = ZonedDateTime.now();

        for (ElliottScreener screener : screeners) {
            if (screener.getNextRunAt() == null || screener.getNextRunAt().isAfter(Instant.now())) {
                continue;
            }

            try {
                log.info("[ElliottScreener] Running screener #{} '{}'", screener.getId(), screener.getName());
                screenerService.runScreener(screener, screener.getUserId());
            } catch (Exception e) {
                log.error("[ElliottScreener] Screener #{} failed: {}", screener.getId(), e.getMessage(), e);
                try {
                    Instant nextRun = CronExpression.parse(screener.getScheduleCron()).next(now).toInstant();
                    screener.setNextRunAt(nextRun);
                    screenerRepository.save(screener);
                } catch (Exception cronEx) {
                    log.error("[ElliottScreener] Could not compute nextRunAt for screener #{}", screener.getId());
                }
            }
        }
    }
}
