package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.config.HistoricalDateLimit;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.Subscription;
import com.dtech.kitecon.data.SubscriptionUow;
import com.dtech.kitecon.enums.SubscriptionUowStatus;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import com.dtech.kitecon.repository.SubscriptionUowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;

@Component
@Slf4j
@RequiredArgsConstructor
public class SubscriptionUowHandler {

    private final SubscriptionUowRepository uowRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final InstrumentRepository instrumentRepository;
    private final HistoricalMarketFetcher marketFetcher;
    private final HistoricalDateLimit historicalDateLimit;

    @Value("${data.uow.retryBackoffSeconds:120}")
    private int retryBackoffSeconds;

    @Transactional
    public void processOne(SubscriptionUow uow) {
        if (uow == null) return;

        String symbol = uow.getTradingSymbol();
        String[] exchanges = uow.getExchange() != null ? new String[]{uow.getExchange()} : new String[]{"NSE", "NFO"};
        try {
            Instrument inst = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, exchanges);
            if (inst == null) {
                throw new IllegalStateException("Instrument not found for " + symbol);
            }

            Interval interval = uow.getInterval();
            Instant startInstant;
            if (uow.getLatestTimestamp() != null) {
                startInstant = null; // incremental mode inside fetcher
            } else {
                int days = historicalDateLimit.getTotalAvailableDuration(inst.getExchange(), interval);
                startInstant = Instant.now().minus(Duration.ofDays(days));
            }

            marketFetcher.fetchAndPersist(inst, interval, startInstant);
            Instant now = Instant.now();
            uow.setLastUpdatedAt(now);
            uow.setNextRunAt(now.plusSeconds(periodFor(interval)));
            uow.setStatus(SubscriptionUowStatus.ACTIVE);
            uow.setLastTradedPrice(uowRepository.getLatestCandleClose(inst.getTradingsymbol(), interval.name()));
            uow.setErrorMessage(null);
            Subscription parentSubscription = uow.getParentSubscription();
            uowRepository.save(uow);
            Subscription sub = subscriptionRepository.findById(parentSubscription.getId()).get();
            sub.setLastTradedPrice(
                    subscriptionRepository.getLatestClosePriceFromCandle(inst.getTradingsymbol(), interval.name()));
            subscriptionRepository.save(sub);
        } catch (Throwable t) {
            Instant now = Instant.now();
            log.error("Error in UWO work ", t);
            log.warn("UOW processing failed for {} {}: {}", symbol, uow.getInterval(), t.getMessage());
            uow.setStatus(SubscriptionUowStatus.FAILED);
            uow.setLastUpdatedAt(now);
            uow.setNextRunAt(now.plusSeconds(retryBackoffSeconds));
            uow.setErrorMessage(t.getMessage());
            uowRepository.save(uow);
        }
    }

    // Reasonable default periods (seconds) per interval; tune via config later if needed
    private long periodFor(Interval interval) {
        return interval.getOffset();
    }
}
