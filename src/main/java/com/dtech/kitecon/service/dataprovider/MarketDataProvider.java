package com.dtech.kitecon.service.dataprovider;

import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Quote;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Interface for market data providers
 * Implementations can fetch data from different sources (Database, Zerodha API, etc.)
 */
public interface MarketDataProvider {

    /**
     * Get the provider name/type
     */
    String getProviderName();

    /**
     * Check if this provider is available/configured
     */
    boolean isAvailable();

    /**
     * Get priority for provider selection (higher = higher priority)
     */
    int getPriority();

    /**
     * Load bar series for a given configuration
     *
     * @param config Bar series configuration
     * @return IntervalBarSeries with OHLCV data
     */
    IntervalBarSeries loadBarSeries(BarSeriesConfig config) throws Exception;

    /**
     * Get live quote for a symbol
     *
     * @param symbol Stock symbol
     * @return Quote data
     */
    Quote getQuote(String symbol) throws Exception;

    /**
     * Get quotes for multiple symbols
     *
     * @param symbols List of stock symbols
     * @return Map of symbol to Quote
     */
    Map<String, Quote> getQuotes(List<String> symbols) throws Exception;

    /**
     * Get historical OHLC data
     *
     * @param instrumentToken Instrument token
     * @param from Start date
     * @param to End date
     * @param interval Timeframe interval
     * @param continuous Whether to fetch continuous data
     * @return List of historical data points
     */
    List<HistoricalData> getHistoricalData(
        Long instrumentToken,
        LocalDate from,
        LocalDate to,
        String interval,
        boolean continuous
    ) throws Exception;
}
