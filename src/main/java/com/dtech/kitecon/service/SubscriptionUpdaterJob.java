package com.dtech.kitecon.service;

import com.dtech.kitecon.data.Subscription;
import com.dtech.kitecon.repository.SubscriptionRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Daily job to update subscriptions to the latest data.
 * - Finds subscriptions with lastUpdatedAt older than 1 hour (configurable)
 * - Resolves underlying + nearest 2 futures + 5 options (current series)
 * - Uses HistoricalMarketFetcher to fetch today's historical candles (rate-limited)
 * - Rewrites candle records and updates subscription.lastUpdatedAt
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionUpdaterJob {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionUowGenerator subscriptionUowGenerator;
    private final DataFetchService dataFetchService;
    private final SubscriptionUowHandler subscriptionUowHandler;

    @Value("${data.update.perRunCap:10000}")
    private int perRunCap;

    @Value("${data.update.status:ACTIVE}")
    private String activeStatus;

    @Value("${data.update.intervals:OneHour}")
    private String intervalsProperty;

    // Enable/disable the scheduled updater via API (true by default)
    @Setter
    @Getter
    private volatile boolean enabled = true;

    // Repository for expanding INDEX-<name> subscriptions into constituent symbols
    private final com.dtech.kitecon.repository.IndexSymbolRepository indexSymbolRepository;

    /**
     * Run hourly by default. Cron can be overridden using data.update.hourlyCron property.
     */
    @Scheduled(cron = "${data.update.hourlyCron:0 0 1 * * ?}")
    public void runUpdateJob() {
        try {
            dataFetchService.downloadAllInstruments();
            if (!enabled) {
                log.info("SubscriptionUpdaterJob is disabled; skipping execution.");
                return;
            }
            Instant cutoff = Instant.now().minusSeconds(3600);
            List<Subscription> subscriptions = subscriptionRepository.findAllByLatestTimestampBeforeAndStatus(cutoff, activeStatus);
            subscriptions.addAll(
                    subscriptionRepository.findAllByLatestTimestampIsNullAndStatus(activeStatus));
            if (subscriptions.isEmpty()) {
                log.info("SubscriptionUpdaterJob: no stale subscriptions to process.");
                return;
            }

            log.info("SubscriptionUpdaterJob: processing {} subscriptions", subscriptions.size());

            for (Subscription s : subscriptions) {
                try {
                    String symbol = s.getTradingSymbol();
                    if (symbol != null && symbol.startsWith("INDEX-")) {
                        String indexName = symbol.substring("INDEX-".length()).trim();
                        List<String> members = indexSymbolRepository.findAllSymbolsByIndexName(indexName);
                        if (members == null || members.isEmpty()) {
                            log.warn("No members found for index {}; marking subscription {} as updated with no action", indexName, symbol);
                            s.setLastUpdatedAt(java.time.LocalDateTime.now());
                            subscriptionRepository.save(s);
                            continue;
                        }
                        for (String member : members) {
                            subscriptionUowGenerator.processSubscriptionForSymbol(s, member, false);
                        }
                        // Update parent once after scheduling all members
                        s.setLastUpdatedAt(java.time.LocalDateTime.now());
                        subscriptionRepository.save(s);
                    } else {
                        // Plain index names (e.g., NIFTY50) are treated as single symbols
                        subscriptionUowGenerator.processSubscription(s);
                    }
                } catch (Throwable t) {
                    log.error("Failed to process subscription {}: {}", s.getTradingSymbol(), t.getMessage(), t);
                }
            }
        } catch (Throwable t) {
            log.error("SubscriptionUpdaterJob encountered an unexpected error: {}", t.getMessage(), t);
        }
    }

}
