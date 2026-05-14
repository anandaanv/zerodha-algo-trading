package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AiTraderConfig;
import com.dtech.aitrader.repository.AiTraderConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConfigBootstrap {
    private final AiTraderConfigRepository configRepo;

    @jakarta.annotation.PostConstruct
    public void bootstrap() {
        log.info("Bootstrapping AI Trader config...");

        ensureConfig("levels.model", "claude-sonnet-4-5-20251022");
        ensureConfig("pattern.model", "claude-sonnet-4-5-20251022");
        ensureConfig("analyse.model", "claude-sonnet-4-5-20251022");
        ensureConfig("levels.batch_size", "5");
        ensureConfig("levels.touch_tolerance_pct", "0.40");
        ensureConfig("pattern.enabled", "false");
        ensureConfig("paper_trade.enabled", "true");
        ensureConfig("ai_lines.suppression_tolerance_pct", "1.0");
        ensureConfig("ai_analyse.max_per_symbol_per_day", "5");
        ensureConfig("trail_stop.enabled", "false");
        ensureConfig("trail_stop.check_interval_hours", "4");
        ensureConfig("watch_trade.monitor.enabled", "false");

        log.info("AI Trader config bootstrap complete");
    }

    private void ensureConfig(String key, String value) {
        if (!configRepo.existsByConfigKey(key)) {
            AiTraderConfig config = AiTraderConfig.builder()
                    .configKey(key)
                    .configValue(value)
                    .updatedAt(LocalDateTime.now())
                    .build();
            configRepo.save(config);
            log.debug("Inserted default config: {} = {}", key, value);
        }
    }
}
