package com.dtech.kitecon.service;

import com.dtech.algo.screener.InstrumentResolver;
import com.dtech.algo.screener.SeriesSpec;
import com.dtech.algo.screener.enums.SeriesEnum;
import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.InstrumentLtp;
import com.dtech.kitecon.data.Subscription;
import com.dtech.kitecon.data.SubscriptionUow;
import com.dtech.kitecon.data.SubscriptionUowMapping;
import com.dtech.kitecon.market.fetch.DataFetchException;
import com.dtech.kitecon.market.fetch.MarketDataFetch;
import com.dtech.kitecon.repository.InstrumentLtpRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import com.dtech.kitecon.enums.SubscriptionUowStatus;
import com.dtech.kitecon.repository.SubscriptionUowRepository;
import com.dtech.kitecon.repository.SubscriptionUowMappingRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Component
@AllArgsConstructor
@Slf4j
public class SubscriptionUowGenerator {
    private final SubscriptionRepository subscriptionRepository;
    private final InstrumentRepository instrumentRepository;
    private final SubscriptionUowRepository subscriptionUowRepository;
    private final SubscriptionUowMappingRepository subscriptionUowMappingRepository;
    private final ObjectMapper objectMapper;
    private final InstrumentResolver instrumentResolver;
    private final InstrumentLtpRepository instrumentLtpRepository;
    private final MarketDataFetch marketDataFetch;

    @Transactional
    public void processSubscription(Subscription s) {
        String tradingSymbol = s.getTradingSymbol();
        log.debug("Processing subscription: {}", tradingSymbol);

        List<SeriesSpec> aliasList = parseAliasList(s.getAliasList());
        scheduleForSymbol(s, tradingSymbol, aliasList);

        s.setLastUpdatedAt(LocalDateTime.now());
        subscriptionRepository.save(s);
        log.info("Subscription {} scheduled/updated UOWs at {}", tradingSymbol, s.getLastUpdatedAt());
    }

    @Transactional
    public void processSubscriptionForSymbol(Subscription parent, String tradingSymbol, boolean updateParentTimestamp) {
        log.debug("Processing symbol {} under subscription id={}", tradingSymbol, parent.getId());

        List<SeriesSpec> aliasList = parseAliasList(parent.getAliasList());
        scheduleForSymbol(parent, tradingSymbol, aliasList);

        if (updateParentTimestamp) {
            parent.setLastUpdatedAt(LocalDateTime.now());
            subscriptionRepository.save(parent);
            log.info("Parent subscription {} scheduled/updated via symbol {} at {}", parent.getId(), tradingSymbol, parent.getLastUpdatedAt());
        }
    }

    private List<SeriesSpec> parseAliasList(String aliasListJson) {
        if (aliasListJson == null || aliasListJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(aliasListJson, new TypeReference<List<SeriesSpec>>() {});
        } catch (Exception e) {
            log.error("Failed to parse aliasList: {}", aliasListJson, e);
            return Collections.emptyList();
        }
    }

    private boolean scheduleForSymbol(Subscription parent, String tradingSymbol, List<SeriesSpec> aliasList) {
        Instrument underlying = instrumentRepository.findByTradingsymbolAndExchangeIn(tradingSymbol, new String[]{"NSE"});
        if (underlying == null) {
            log.warn("Underlying {} not found in NSE instruments; skipping under parent {}", tradingSymbol, parent.getId());
            return false;
        }

        // If aliasList is empty, do nothing (no UOWs to create)
        if (aliasList.isEmpty()) {
            log.debug("No aliasList provided for subscription {}, skipping UOW creation", tradingSymbol);
            return true;
        }

        // Fetch LTP once for all option resolutions
        // Try LTP table first, fallback to MarketDataFetch if not available
        Double ltp = instrumentLtpRepository.findByTradingSymbol(underlying.getTradingsymbol())
                .map(InstrumentLtp::getLtp)
                .orElse(null);

        if (ltp == null) {
            log.debug("No LTP found in table for {} (token: {}), fetching from market data", tradingSymbol, underlying.getInstrumentToken());
            try {
                ltp = marketDataFetch.getLastPrice(underlying);
                log.debug("Fetched LTP {} from market data for {}", ltp, tradingSymbol);
            } catch (DataFetchException e) {
                log.warn("Failed to fetch LTP for {}, will use fallback resolver: {}", tradingSymbol, e.getMessage());
            }
        } else {
            log.debug("Using LTP {} from table for {}", ltp, tradingSymbol);
        }

        // Process each alias from aliasList - reusing ScreenerContextLoader logic
        for (SeriesSpec spec : aliasList) {
            SeriesEnum reference = spec.reference();
            Interval timeframe = Interval.valueOf(spec.interval());

            // Use InstrumentResolver - same logic as ScreenerContextLoader
            String instrumentToLoad = instrumentResolver.resolveInstrument(tradingSymbol, reference, ltp);

            // Find the actual instrument from database
            Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(
                    instrumentToLoad, new String[]{"NSE", "NFO"});

            if (instrument != null) {
                String tag = reference.toJson();
                upsertUowActive(parent, instrument, tag, timeframe);
            } else {
                log.warn("Could not find instrument {} for alias {} (reference={}, interval={})",
                        instrumentToLoad, spec, reference, spec.interval());
            }
        }

        return true;
    }

    private void upsertUowActive(Subscription parent, Instrument instrument, String tag, Interval timeframe) {
        String tradingSymbol = instrument.getTradingsymbol();

        var existing = subscriptionUowRepository
                .findByTradingSymbolAndTimeframe(tradingSymbol, timeframe)
                .orElse(null);

        SubscriptionUow uow;
        if (existing == null) {
            uow = SubscriptionUow.builder()
                    .tradingSymbol(tradingSymbol)
                    .exchange(instrument.getExchange())
                    .instrumentTag(tag)
                    .timeframe(timeframe)
                    .status(SubscriptionUowStatus.ACTIVE)
                    .lastUpdatedAt(null)
                    .latestTimestamp(parent.getLatestTimestamp())
                    .nextRunAt(Instant.now())
                    .build();
            uow = subscriptionUowRepository.save(uow);
        } else {
            uow = existing;
            boolean changed = false;
            if (tag != null && !tag.equals(existing.getInstrumentTag())) {
                existing.setInstrumentTag(tag);
                changed = true;
            }
            if (existing.getNextRunAt() == null) {
                existing.setNextRunAt(Instant.now());
                changed = true;
            }
            if (changed) {
                subscriptionUowRepository.save(existing);
            }
        }

        // Create mapping if it doesn't exist
        if (!subscriptionUowMappingRepository.existsBySubscriptionIdAndSubscriptionUowId(parent.getId(), uow.getId())) {
            SubscriptionUowMapping mapping = SubscriptionUowMapping.builder()
                    .subscription(parent)
                    .subscriptionUow(uow)
                    .build();
            subscriptionUowMappingRepository.save(mapping);
        }
    }
}
