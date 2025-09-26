package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.SubscriptionUow;
import com.dtech.kitecon.enums.SubscriptionUowStatus;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import com.dtech.kitecon.repository.SubscriptionUowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionUowService {

    private final SubscriptionUowRepository uowRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final InstrumentRepository instrumentRepository;
    private final HistoricalMarketFetcher marketFetcher;
    private final com.dtech.kitecon.config.HistoricalDateLimit historicalDateLimit;

    @Value("${data.uow.batchSize:2000}")
    private int batchSize;

    @Value("${data.uow.retryBackoffSeconds:120}")
    private int retryBackoffSeconds;

    @Scheduled(
            initialDelayString = "${data.uow.initial-delay:1000}",
            fixedDelayString = "${data.uow.fixed-delay:1000}"
    )
    @Transactional
    public void tick() {
        Instant now = com.dtech.kitecon.misc.TimeUtils.nowIst();
        Set<SubscriptionUowStatus> statuses = EnumSet.of(SubscriptionUowStatus.ACTIVE, SubscriptionUowStatus.FAILED);
        List<SubscriptionUow> batch = uowRepository.findTop2000ByStatusInAndNextRunAtLessThanEqualOrderByNextRunAtAsc(statuses, now);
        if (batch.isEmpty()) {
            return;
        }
        int processed = 0;
        for (SubscriptionUow uow : batch) {
            if (processed >= batchSize) break;
            try {
                if (!claim(uow.getId())) continue;
                processOne(uow.getId());
                processed++;
            } catch (Exception e) {
                log.warn("Error processing UOW id={}: {}", uow.getId(), e.getMessage());
            }
        }
    }

    private boolean claim(Long uowId) {
        return uowRepository.findById(uowId).map(u -> {
            if (u.getStatus() == SubscriptionUowStatus.WIP) return false;
            u.setStatus(SubscriptionUowStatus.WIP);
            try {
                uowRepository.save(u);
                return true;
            } catch (Exception optimistic) {
                return false;
            }
        }).orElse(false);
    }

    public void processOne(Long uowId) {
        SubscriptionUow uow = uowRepository.findById(uowId).orElse(null);
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
                startInstant = com.dtech.kitecon.misc.TimeUtils.nowIst().minus(Duration.ofDays(days));
            }

            Optional<Instant> latestOpt = marketFetcher.fetchAndPersist(inst, interval, startInstant);
            Instant now = com.dtech.kitecon.misc.TimeUtils.nowIst();
            if (latestOpt != null && latestOpt.isPresent()) {
                Instant latest = latestOpt.get();
                if (uow.getLatestTimestamp() == null || latest.isAfter(uow.getLatestTimestamp())) {
                    uow.setLatestTimestamp(latest);
                    // bump parent latestTimestamp if advanced
                    var parent = uow.getParentSubscription();
                    if (parent != null && (parent.getLatestTimestamp() == null || latest.isAfter(parent.getLatestTimestamp()))) {
                        parent.setLatestTimestamp(latest);
                        subscriptionRepository.save(parent);
                    }
                }
            }
            uow.setLastUpdatedAt(now);
            uow.setNextRunAt(now.plusSeconds(periodFor(interval)));
            uow.setStatus(SubscriptionUowStatus.ACTIVE);
            uow.setErrorMessage(null);
            uowRepository.save(uow);
        } catch (Throwable t) {
            Instant now = com.dtech.kitecon.misc.TimeUtils.nowIst();
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
