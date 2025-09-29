package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.Subscription;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import com.dtech.kitecon.repository.SubscriptionUowRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@AllArgsConstructor
@Slf4j
public class SubscriptionUowGenerator {
    private final SubscriptionRepository subscriptionRepository;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentsResolverService resolverService;
    private final SubscriptionUowRepository subscriptionUowRepository;

    @Transactional
    protected void processSubscription(Subscription s, List<Interval> intervals) {
        String tradingSymbol = s.getTradingSymbol();
        log.debug("Processing subscription: {}", tradingSymbol);

        // Delegate core scheduling to shared helper
        scheduleForSymbol(s, tradingSymbol, intervals);

        // Update subscription timestamp to reflect scheduling step (even if no-op, preserves existing behavior)
        s.setLastUpdatedAt(LocalDateTime.now());
        subscriptionRepository.save(s);
        log.info("Subscription {} scheduled/updated UOWs at {}", tradingSymbol, s.getLastUpdatedAt());
    }

    /**
     * Processes a specific tradingSymbol under the provided parent subscription.
     * Does NOT mutate the parent's tradingSymbol; optionally updates parent's lastUpdatedAt.
     */
    @Transactional
    public void processSubscriptionForSymbol(Subscription parent, String tradingSymbol, List<Interval> intervals, boolean updateParentTimestamp) {
        log.debug("Processing symbol {} under subscription id={}", tradingSymbol, parent.getId());

        // Delegate core scheduling to shared helper
        scheduleForSymbol(parent, tradingSymbol, intervals);

        if (updateParentTimestamp) {
            parent.setLastUpdatedAt(LocalDateTime.now());
            subscriptionRepository.save(parent);
            log.info("Parent subscription {} scheduled/updated via symbol {} at {}", parent.getId(), tradingSymbol, parent.getLastUpdatedAt());
        }
    }

    /**
     * Shared core logic to schedule UOWs for a given parent subscription and trading symbol.
     * Returns true if underlying was found and scheduling proceeded, false otherwise.
     */
    private boolean scheduleForSymbol(Subscription parent, String tradingSymbol, List<Interval> intervals) {
        // Validate underlying exists in NSE
        Instrument underlying = instrumentRepository.findByTradingsymbolAndExchangeIn(tradingSymbol, new String[]{"NSE"});
        if (underlying == null) {
            log.warn("Underlying {} not found in NSE instruments; skipping under parent {}", tradingSymbol, parent.getId());
            return false;
        }

        // Compute related instruments and upsert UOWs (no inactivation)
        List<Instrument> futures = resolverService.resolveNearestFutures(tradingSymbol, 2);
        Set<Instrument> instrumentsToUpdate = new LinkedHashSet<Instrument>(futures);
        List<Instrument> options = resolverService.resolveTopOptions(tradingSymbol, underlying, 10);
        instrumentsToUpdate.addAll(options);

        log.debug("Resolved {} instruments for {} (futures={}, options={}) under parent {}",
                instrumentsToUpdate.size(), tradingSymbol, futures.size(), options.size(), parent.getId());

        // Underlying (SPOT)
        for (Interval interval : intervals) {
            this.upsertUowActive(parent, underlying.getTradingsymbol(), "SPOT", underlying.getExchange(), interval);
        }

        for (Instrument fut : futures) {
            String tag = "FUT";
            for (Interval interval : intervals) {
                this.upsertUowActive(parent, fut.getTradingsymbol(), tag, fut.getExchange(), interval);
            }
        }

        int optIndex = 1;
        for (Instrument opt : options) {
            String tag = deriveOptionTag(opt.getTradingsymbol(), optIndex++);
            for (Interval interval : intervals) {
                this.upsertUowActive(parent, opt.getTradingsymbol(), tag, opt.getExchange(), interval);
            }
        }

        return true;
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