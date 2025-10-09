package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.config.HistoricalDateLimit;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.Subscription;
import com.dtech.kitecon.data.SubscriptionUow;
import com.dtech.kitecon.enums.SubscriptionUowStatus;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import com.dtech.kitecon.repository.SubscriptionUowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class SubscriptionUowHandler {

    private final SubscriptionUowRepository uowRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final InstrumentRepository instrumentRepository;
    private final HistoricalMarketFetcher marketFetcher;
    private final HistoricalDateLimit historicalDateLimit;
    private final CandleRepository candleRepository;
    private final MarketHoursService marketHoursService;

    @Value("${data.uow.retryBackoffSeconds:120}")
    private int retryBackoffSeconds;

    @Transactional
    @Async
    public void processOne(SubscriptionUow uow) {
        if (uow == null) return;

        // Simple after-hours guard: if after close and no override, schedule next run at next market open and exit
        if (marketHoursService.isAfterMarketNow() && !marketHoursService.isOverrideEnabled()) {
            Instant nextOpen = marketHoursService.nextMarketOpenAfterNow();
            uow.setNextRunAt(nextOpen);
            uow.setStatus(SubscriptionUowStatus.ACTIVE);
            uowRepository.save(uow);
            log.debug("After market hours; scheduling UOW {} for next open at {}", uow.getId(), nextOpen);
            return;
        }

        String symbol = uow.getTradingSymbol();
        String[] exchanges = uow.getExchange() != null ? new String[]{uow.getExchange()} : new String[]{"NSE", "NFO"};
        try {
            Instrument inst = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, exchanges);
            if (inst == null) {
                throw new IllegalStateException("Instrument not found for " + symbol);
            }
            if(inst.getExpiry()!= null && inst.getExpiry().isBefore(LocalDateTime.now())) {
                uow.setStatus(SubscriptionUowStatus.INACTIVE);
                uowRepository.save(uow);
                log.info("Instrument {} expired, skipping UOW {}", symbol, uow.getId());
                return;
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
//            Double LTP = candleRepository.findFirstByInstrumentAndTimeframeOrderByTimestampDesc(inst, interval).getClose();
//            uow.setLastTradedPrice(LTP);
            uow.setErrorMessage(null);
            Subscription parentSubscription = uow.getParentSubscription();
            uowRepository.save(uow);
            Subscription sub = subscriptionRepository.findById(parentSubscription.getId()).get();
//            sub.setLastTradedPrice(LTP);
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
