package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.SubscriptionUow;
import com.dtech.kitecon.enums.SubscriptionUowStatus;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import com.dtech.kitecon.repository.SubscriptionUowRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionUowService {

    private final SubscriptionUowHandler subscriptionUowHandler;
    private final SubscriptionUowRepository uowRepository;

    @Value("${data.uow.batchSize:2000}")
    private int batchSize;

    @Scheduled(
            initialDelayString = "${data.uow.initial-delay:1000}",
            fixedDelayString = "${data.uow.fixed-delay:1000}"
    )
    public void tick() {
        Instant now = Instant.now();
        Set<SubscriptionUowStatus> statuses = EnumSet.of(SubscriptionUowStatus.ACTIVE, SubscriptionUowStatus.FAILED);
        List<SubscriptionUow> batch = uowRepository.findByStatusInAndNextRunAtLessThanEqualOrderByNextRunAtAsc(statuses, now);
        if (batch.isEmpty()) {
            return;
        }
        int processed = 0;
        for (SubscriptionUow uow : batch) {
            if (processed >= batchSize) break;
            try {
                subscriptionUowHandler.processOne(uow);
                processed++;
            } catch (Exception e) {
                log.warn("Error processing UOW id={}: {}", uow.getId(), e.getMessage());
            }
        }
    }
}
