package com.dtech.kitecon.service.dataprovider;

import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Quote;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Service that delegates market data requests to appropriate providers
 * Can route to Database, Zerodha, or other providers based on configuration and availability
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataProviderService {

    private final List<MarketDataProvider> providers;

    @Value("${market.data.provider:zerodha}")
    private String preferredProvider;

    private MarketDataProvider selectedProvider;

    @PostConstruct
    public void init() {
        // Select provider based on preference and availability
        selectedProvider = providers.stream()
            .filter(MarketDataProvider::isAvailable)
            .filter(p -> preferredProvider.equalsIgnoreCase(p.getProviderName()))
            .findFirst()
            .orElseGet(() -> {
                // Fallback to highest priority available provider
                MarketDataProvider fallback = providers.stream()
                    .filter(MarketDataProvider::isAvailable)
                    .max(Comparator.comparingInt(MarketDataProvider::getPriority))
                    .orElse(null);

                if (fallback != null) {
                    log.warn("Preferred provider '{}' not available, using fallback: {}",
                        preferredProvider, fallback.getProviderName());
                } else {
                    log.error("No market data providers available!");
                }

                return fallback;
            });

        if (selectedProvider != null) {
            log.info("Market Data Provider initialized: {} (preferred: {})",
                selectedProvider.getProviderName(), preferredProvider);
        }
    }

    /**
     * Get the currently selected provider
     */
    public MarketDataProvider getSelectedProvider() {
        return selectedProvider;
    }

    /**
     * Load bar series using the selected provider
     */
    public IntervalBarSeries loadBarSeries(BarSeriesConfig config) throws Exception {
        if (selectedProvider == null) {
            throw new IllegalStateException("No market data provider available");
        }

        log.debug("Loading bar series using {} provider for {} - {}",
            selectedProvider.getProviderName(), config.getInstrument(), config.getInterval());

        return selectedProvider.loadBarSeries(config);
    }

    /**
     * Get live quote for a symbol
     */
    public Quote getQuote(String symbol) throws Exception {
        if (selectedProvider == null) {
            throw new IllegalStateException("No market data provider available");
        }

        return selectedProvider.getQuote(symbol);
    }

    /**
     * Get quotes for multiple symbols
     */
    public Map<String, Quote> getQuotes(List<String> symbols) throws Exception {
        if (selectedProvider == null) {
            throw new IllegalStateException("No market data provider available");
        }

        return selectedProvider.getQuotes(symbols);
    }

    /**
     * Get historical OHLC data
     */
    public List<HistoricalData> getHistoricalData(
        Long instrumentToken,
        LocalDate from,
        LocalDate to,
        String interval,
        boolean continuous
    ) throws Exception {
        if (selectedProvider == null) {
            throw new IllegalStateException("No market data provider available");
        }

        return selectedProvider.getHistoricalData(instrumentToken, from, to, interval, continuous);
    }

    /**
     * Get a specific provider by name
     */
    public MarketDataProvider getProvider(String providerName) {
        return providers.stream()
            .filter(p -> providerName.equalsIgnoreCase(p.getProviderName()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Switch to a different provider at runtime
     */
    public boolean switchProvider(String providerName) {
        MarketDataProvider newProvider = providers.stream()
            .filter(p -> providerName.equalsIgnoreCase(p.getProviderName()))
            .filter(MarketDataProvider::isAvailable)
            .findFirst()
            .orElse(null);

        if (newProvider != null) {
            selectedProvider = newProvider;
            log.info("Switched market data provider to: {}", providerName);
            return true;
        }

        log.warn("Provider '{}' not available for switching", providerName);
        return false;
    }
}
