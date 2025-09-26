package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.Subscription;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hourly job to update subscriptions to the latest data.
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
    private final InstrumentRepository instrumentRepository;
    private final InstrumentsResolverService resolverService;
    private final HistoricalMarketFetcher marketFetcher;
    private final com.dtech.kitecon.config.HistoricalDateLimit historicalDateLimit;
    private final com.dtech.kitecon.repository.SubscriptionUowRepository subscriptionUowRepository;

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

    /**
     * Run hourly by default. Cron can be overridden using data.update.hourlyCron property.
     */
    @Scheduled(cron = "${data.update.hourlyCron:0 * * * * ?}")
    @Transactional
    public void runUpdateJob() {
        try {
            if (!enabled) {
                log.info("SubscriptionUpdaterJob is disabled; skipping execution.");
                return;
            }
            Instant cutoff = com.dtech.kitecon.misc.TimeUtils.nowIst().minusSeconds(3600);
            List<Subscription> subscriptions = subscriptionRepository.findAllByLatestTimestampBeforeAndStatus(cutoff, activeStatus);
            subscriptions.addAll(
                    subscriptionRepository.findAllByLatestTimestampIsNullAndStatus(activeStatus));
            if (subscriptions.isEmpty()) {
                log.info("SubscriptionUpdaterJob: no stale subscriptions to process.");
                return;
            }

            List<Interval> intervals = Arrays.stream(Interval.values()).toList();
            log.info("SubscriptionUpdaterJob: processing {} subscriptions with intervals {}", subscriptions.size(), intervals);

            for (Subscription s : subscriptions) {
                try {
                    processSubscription(s, intervals);
                } catch (Throwable t) {
                    log.error("Failed to process subscription {}: {}", s.getTradingSymbol(), t.getMessage(), t);
                }
            }
        } catch (Throwable t) {
            log.error("SubscriptionUpdaterJob encountered an unexpected error: {}", t.getMessage(), t);
        }
    }

    @Transactional
    protected void processSubscription(Subscription s, List<Interval> intervals) {
        String tradingSymbol = s.getTradingSymbol();
        log.debug("Processing subscription: {}", tradingSymbol);

        // Validate underlying exists in NSE
        Instrument underlying = instrumentRepository.findByTradingsymbolAndExchangeIn(tradingSymbol, new String[]{"NSE"});
        if (underlying == null) {
            log.warn("Underlying {} not found in NSE instruments; skipping subscription {}", tradingSymbol, s.getId());
            // still update lastUpdatedAt to avoid repeatedly trying malformed entry? keep as skip-logic:
            // choose to update lastUpdatedAt so this bad entry doesn't block others
            s.setLastUpdatedAt(LocalDateTime.now());
            subscriptionRepository.save(s);
            return;
        }

        // Compute related instruments and upsert UOWs (no inactivation)
        List<Instrument> futures = resolverService.resolveNearestFutures(tradingSymbol, 2);
        Set<Instrument> instrumentsToUpdate = new LinkedHashSet<>(futures);
        List<Instrument> options = resolverService.resolveTopOptions(tradingSymbol, underlying, 10);
        instrumentsToUpdate.addAll(options);

        log.debug("Resolved {} instruments for {} (futures={}, options={})",
                instrumentsToUpdate.size(), tradingSymbol, futures.size(), options.size());

        // Underlying (SPOT)
        for (Interval interval : intervals) {
            upsertUowActive(s, underlying.getTradingsymbol(), "SPOT", underlying.getExchange(), interval);
        }

        for (Instrument fut : futures) {
            String tag = "FUT";
            for (Interval interval : intervals) {
                upsertUowActive(s, fut.getTradingsymbol(), tag, fut.getExchange(), interval);
            }
        }

        int optIndex = 1;
        for (Instrument opt : options) {
            String tag = deriveOptionTag(opt.getTradingsymbol(), optIndex++);
            for (Interval interval : intervals) {
                upsertUowActive(s, opt.getTradingsymbol(), tag, opt.getExchange(), interval);
            }
        }

        // Update subscription timestamp to reflect scheduling step
        s.setLastUpdatedAt(LocalDateTime.now());
        subscriptionRepository.save(s);
        log.info("Subscription {} scheduled/updated UOWs at {}", tradingSymbol, s.getLastUpdatedAt());
    }

    private static String deriveOptionTag(String tradingSymbol, int index) {
        String upper = tradingSymbol == null ? "" : tradingSymbol.toUpperCase();
        if (upper.contains("CE")) return "CE" + index;
        if (upper.contains("PE")) return "PE" + index;
        return "OPT" + index;
    }

    private void upsertUowActive(Subscription parent, String symbol, String tag, String exchange, Interval interval) {
        var existing = subscriptionUowRepository
                .findByParentSubscriptionIdAndTradingSymbolAndInterval(parent.getId(), symbol, interval)
                .orElse(null);
        if (existing == null) {
            var u = com.dtech.kitecon.data.SubscriptionUow.builder()
                    .parentSubscription(parent)
                    .tradingSymbol(symbol)
                    .exchange(exchange)
                    .instrumentTag(tag)
                    .interval(interval)
                    .status(com.dtech.kitecon.enums.SubscriptionUowStatus.ACTIVE)
                    .lastUpdatedAt(null)
                    .latestTimestamp(parent.getLatestTimestamp()) // seed from parent
                    .nextRunAt(Instant.now())
                    .build();
            subscriptionUowRepository.save(u);
        } else {
            // Update metadata if changed; keep status as-is (no inactivation/reactivation churn)
            boolean changed = false;
            if (tag != null && !tag.equals(existing.getInstrumentTag())) { existing.setInstrumentTag(tag); changed = true; }
            if (exchange != null && (existing.getExchange() == null || !exchange.equals(existing.getExchange()))) { existing.setExchange(exchange); changed = true; }
            if (existing.getNextRunAt() == null) { existing.setNextRunAt(Instant.now()); changed = true; }
            if (changed) subscriptionUowRepository.save(existing);
        }
    }
}
