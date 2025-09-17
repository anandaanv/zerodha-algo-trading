package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.Subscription;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
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

    @Value("${data.update.perRunCap:10000}")
    private int perRunCap;

    @Value("${data.update.status:ACTIVE}")
    private String activeStatus;

    @Value("${data.update.intervals:OneHour}")
    private String intervalsProperty;

    /**
     * Run hourly by default. Cron can be overridden using data.update.hourlyCron property.
     */
    @Scheduled(cron = "${data.update.hourlyCron:0 * * * * ?}")
    public void runUpdateJob() {
        try {
            Instant cutoff = Instant.now().minusSeconds(3600);
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

        // Collect instruments: underlying + nearest 2 futures + top 5 options
        for (Interval interval : intervals) {
            updateInstrument(s, interval, underlying, Collections.singleton(underlying));
        }

        List<Instrument> futures = resolverService.resolveNearestFutures(tradingSymbol, 2);
        Set<Instrument> instrumentsToUpdate = new LinkedHashSet<>(futures);

        List<Instrument> options = resolverService.resolveTopOptions(tradingSymbol, underlying, 10);
        instrumentsToUpdate.addAll(options);

        log.debug("Resolved {} instruments for {} (futures={}, options={})",
                instrumentsToUpdate.size(), tradingSymbol, futures.size(), options.size());

        // For each interval, compute start Instant (subscription-level latest). Then for each instrument, fetch & persist (rate limited inside HistoricalMarketFetcher)
        for (Interval interval : intervals) {
            updateInstrument(s, interval, underlying, instrumentsToUpdate);
        }

        // Update subscription timestamp (do not skip existing)
        s.setLastUpdatedAt(LocalDateTime.now());
        subscriptionRepository.save(s);
        log.info("Subscription {} updated at {}", tradingSymbol, s.getLastUpdatedAt());
    }

    public void updateInstrument(Subscription s, Interval interval, Instrument underlying, Set<Instrument> instrumentsToUpdate) {
        // determine start Instant for this subscription+interval
        Instant startInstant;
        if (s.getLatestTimestamp() != null) {
            startInstant = null;
        } else {
            // fallback to HistoricalDateLimit (duration in days) when latest is not present
            int days = historicalDateLimit.getTotalAvailableDuration(underlying.getExchange(), interval);
            startInstant = Instant.now();
            startInstant = startInstant.minus(java.time.Duration.ofDays(days));
        }

        for (Instrument inst : instrumentsToUpdate) {
            try {
                Optional<Instant> latestOpt = marketFetcher.fetchAndPersist(inst, interval, startInstant);
                // update subscription.latestTimestamp to the maximum of existing and returned latest
                if (latestOpt != null && latestOpt.isPresent()) {
                    Instant latestInst = latestOpt.get();
                    if (s.getLatestTimestamp() == null || latestInst.isAfter(s.getLatestTimestamp())) {
                        s.setLatestTimestamp(latestInst);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while fetching {} {}: {}", inst.getTradingsymbol(), interval, ie.getMessage());
            } catch (Throwable t) {
                log.warn("Failed to fetch/persist {} {}: {}", inst.getTradingsymbol(), interval, t.getMessage());
            }
        }
    }

    private List<Interval> parseIntervals(String prop) {
        if (prop == null || prop.isBlank()) return Collections.singletonList(Interval.OneHour);
        String[] parts = prop.split(",");
        return Arrays.stream(parts)
                .map(String::trim)
                .filter(str -> !str.isEmpty())
                .map(name -> {
                    try {
                        return Interval.valueOf(name);
                    } catch (Exception e) {
                        log.warn("Unknown interval {} in property, defaulting to OneHour", name);
                        return Interval.OneHour;
                    }
                })
                .collect(Collectors.toList());
    }
}
