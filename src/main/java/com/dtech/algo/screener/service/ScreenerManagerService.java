package com.dtech.algo.screener.service;

import com.dtech.algo.screener.SeriesSpec;
import com.dtech.algo.screener.db.ScreenerEntity;
import com.dtech.algo.screener.db.ScreenerRepository;
import com.dtech.algo.screener.domain.Screener;
import com.dtech.kitecon.data.Subscription;
import com.dtech.kitecon.repository.IndexSymbolRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import com.dtech.kitecon.service.SubscriptionUowGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service to manage screener subscriptions.
 * Handles INDEX resolution and creates subscription records for screeners.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScreenerManagerService {

    private final ScreenerRepository screenerRepository;
    private final IndexSymbolRepository indexSymbolRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionUowGenerator subscriptionUowGenerator;
    private final ObjectMapper objectMapper;

    /**
     * Creates or updates subscriptions for a screener.
     * Resolves INDEX-xxx symbols to individual stocks and creates subscription records.
     */
    @Transactional
    public void createSubscriptionsForScreener(Long screenerId) {
        ScreenerEntity screener = screenerRepository.findById(screenerId)
                .orElseThrow(() -> new IllegalArgumentException("Screener not found: " + screenerId));

        Screener domain = Screener.fromEntity(screener, objectMapper);

        // Get symbols from scheduling config
        List<String> symbols = new ArrayList<>();
        if (domain.getSchedulingConfig() != null && domain.getSchedulingConfig().getRunConfigs() != null) {
            domain.getSchedulingConfig().getRunConfigs().forEach(rc -> {
                if (rc.getSymbols() != null) {
                    symbols.addAll(rc.getSymbols());
                }
            });
        }

        if (symbols.isEmpty()) {
            log.warn("No symbols found in screener {}, skipping subscription creation", screenerId);
            return;
        }

        // Resolve INDEX symbols
        List<String> resolvedSymbols = resolveSymbols(symbols);

        // Convert mapping to aliasList JSON
        String aliasListJson = convertMappingToAliasListJson(domain.getMapping());

        // Create subscription for each resolved symbol
        for (String symbol : resolvedSymbols) {
            createOrUpdateSubscription(symbol, aliasListJson);
        }

        log.info("Created/updated subscriptions for screener {} with {} symbols", screenerId, resolvedSymbols.size());
    }

    /**
     * Resolves symbols including INDEX-xxx patterns to individual stocks.
     */
    private List<String> resolveSymbols(List<String> symbols) {
        List<String> resolved = new ArrayList<>();

        for (String symbol : symbols) {
            if (symbol != null && symbol.startsWith("INDEX-")) {
                String indexName = symbol.substring("INDEX-".length()).trim();
                List<String> indexSymbols = indexSymbolRepository.findAllSymbolsByIndexName(indexName);
                if (indexSymbols != null && !indexSymbols.isEmpty()) {
                    resolved.addAll(indexSymbols);
                } else {
                    log.warn("No symbols found for index: {}", indexName);
                }
            } else {
                resolved.add(symbol);
            }
        }

        return resolved;
    }

    /**
     * Converts screener mapping (alias -> SeriesSpec) to aliasList JSON array.
     */
    private String convertMappingToAliasListJson(Map<String, SeriesSpec> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return null;
        }

        try {
            List<SeriesSpec> aliasList = new ArrayList<>(mapping.values());
            return objectMapper.writeValueAsString(aliasList);
        } catch (Exception e) {
            log.error("Failed to convert mapping to aliasList JSON", e);
            return null;
        }
    }

    /**
     * Creates or updates a subscription for a symbol with the given aliasList.
     */
    private void createOrUpdateSubscription(String tradingSymbol, String aliasListJson) {
        Subscription existing = subscriptionRepository.findByTradingSymbol(tradingSymbol).orElse(null);

        if (existing != null) {
            // Update existing subscription with new aliasList
            existing.setAliasList(aliasListJson);
            existing.setLastUpdatedAt(LocalDateTime.now());
            subscriptionRepository.save(existing);
            log.debug("Updated subscription for {} with aliasList", tradingSymbol);
        } else {
            // Create new subscription
            Subscription subscription = Subscription.builder()
                    .tradingSymbol(tradingSymbol)
                    .status("ACTIVE")
                    .createdAt(LocalDateTime.now())
                    .lastUpdatedAt(LocalDateTime.now())
                    .aliasList(aliasListJson)
                    .build();
            subscriptionRepository.save(subscription);
            log.debug("Created new subscription for {} with aliasList", tradingSymbol);
        }

        // Trigger UOW generation
//        Subscription subscription = subscriptionRepository.findByTradingSymbol(tradingSymbol).get();
//        subscriptionUowGenerator.processSubscription(subscription);
    }
}
