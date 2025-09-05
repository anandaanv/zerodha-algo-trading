package com.dtech.kitecon.controller;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.runner.candle.DataTick;
import com.dtech.algo.runner.candle.LatestBarSeriesProvider;
import com.dtech.algo.series.Exchange;
import com.dtech.algo.series.InstrumentType;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.config.HistoricalDateLimit;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.DatabaseBatchUpdateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Log4j2
public class BarSeriesHelper {
    private final HistoricalDateLimit historicalDateLimit;
    private final LatestBarSeriesProvider barSeriesLoader;
    private final DatabaseBatchUpdateService databaseBatchUpdateService;
    private final InstrumentRepository instrumentRepository;

    // Cache for registered instrument tokens
    private final Map<Long, String> instrumentTokenToSymbolMap = new ConcurrentHashMap<>();
    /**
     * Creates a BarSeriesConfig for the given symbol and timeframe
     * 
     * @param symbol The instrument symbol
     * @param timeframe The timeframe/interval as string
     * @return A configured BarSeriesConfig
     */
    public BarSeriesConfig createBarSeriesConfig(String symbol, String timeframe) {
        Interval interval = Interval.valueOf(timeframe); // Assuming you have an Interval enum
        int duration = historicalDateLimit.getScreenerDuration("NSE", interval);

        return BarSeriesConfig.builder()
                .interval(interval)
                .exchange(Exchange.NSE)
                .instrument(symbol)
                .instrumentType(InstrumentType.EQ)
                .startDate(LocalDate.now().minusDays(duration))
                .endDate(LocalDate.now())
                .name(symbol + "_" + timeframe)
                .build();
    }

    /**
     * Retrieves an IntervalBarSeries for the given stock and timeframe
     * 
     * @param stock The instrument symbol
     * @param tf The timeframe/interval as string
     * @return The loaded IntervalBarSeries
     */
    public IntervalBarSeries getIntervalBarSeries(String stock, String tf) {
        BarSeriesConfig config = createBarSeriesConfig(stock, tf);
        IntervalBarSeries barSeries = null;
        try {
            barSeries = barSeriesLoader.loadBarSeries(config);
        } catch (StrategyException e) {
            throw new RuntimeException(e);
        }
        return barSeries;
    }

    /**
     * Process a tick update for all intervals
     * 
     * @param tick The market data tick
     * @return true if processing was successful for at least one interval
     */
    public boolean processTick(DataTick tick) {
        boolean anySuccess = false;

        // Process this tick for all intervals
        for (Interval interval : Interval.values()) {
            if (processTick(tick, interval)) {
                anySuccess = true;
            }
        }

        return anySuccess;
    }

    /**
     * Process a tick update for a specific interval
     * 
     * @param tick The market data tick
     * @param interval The specific interval to process the tick for
     * @return true if processing was successful
     */
    public boolean processTick(DataTick tick, Interval interval) {
        try {
            // Get the config for this instrument token with the specified interval
            BarSeriesConfig config = getConfigForInstrumentToken(tick.getInstrumentToken(), interval);
            if (config == null) {
                log.warn("No config found for instrument token: {} with interval {}", tick.getInstrumentToken(), interval);
                return false;
            }

            // Load the bar series first
            IntervalBarSeries barSeries;
            try {
                barSeries = barSeriesLoader.loadBarSeries(config);
                if (barSeries == null) {
                    log.warn("Failed to load bar series for config: {}", config.getName());
                    return false;
                }
            } catch (StrategyException e) {
                log.error("Error loading bar series for config: {}", config.getName(), e);
                return false;
            }

            // Update the bar series with this tick
            // The method returns the completed bar (previous bar) if a new bar was created, or null otherwise
            Bar completedBar = barSeriesLoader.updateBarSeries(tick, barSeries);

            // If a completed bar was returned, add it to the batch update queue
            if (completedBar != null) {
                log.debug("Completed bar detected for instrument: {}, interval: {}, time: {}, adding to batch queue", 
                         config.getInstrument(), interval, completedBar.getEndTime());
                databaseBatchUpdateService.addToQueue(config);
            }

            return true;
        } catch (Exception e) {
            log.error("Error processing tick for instrument token: {} with interval: {}", tick.getInstrumentToken(), interval, e);
            return false;
        }
    }

    /**
     * Gets the BarSeriesConfig for the given instrument token with default OneMinute interval
     * 
     * @param instrumentToken The instrument token from Kite
     * @return The BarSeriesConfig or null if not found
     */
    public BarSeriesConfig getConfigForInstrumentToken(long instrumentToken) {
        return getConfigForInstrumentToken(instrumentToken, Interval.OneMinute);
    }

    /**
     * Gets the BarSeriesConfig for the given instrument token and interval
     * 
     * @param instrumentToken The instrument token from Kite
     * @param interval The interval for the bar series
     * @return The BarSeriesConfig or null if not found
     */
    public BarSeriesConfig getConfigForInstrumentToken(long instrumentToken, Interval interval) {
        try {
            // Check if we have the trading symbol in cache
            String tradingSymbol = instrumentTokenToSymbolMap.get(instrumentToken);

            // If not in cache, look up from repository
            if (tradingSymbol == null) {
                Instrument instrument = instrumentRepository.findById(instrumentToken).orElse(null);
                if (instrument == null) {
                    log.warn("No instrument found for token: {}", instrumentToken);
                    return null;
                }
                tradingSymbol = instrument.getTradingsymbol();

                // Cache the trading symbol for future lookups
                instrumentTokenToSymbolMap.put(instrumentToken, tradingSymbol);
                log.info("Added instrument token to cache: {} -> {}", instrumentToken, tradingSymbol);
            }

            // Create config on the fly
            return createBarSeriesConfig(tradingSymbol, interval.name());
        } catch (Exception e) {
            log.error("Error looking up config for instrument token: {} with interval: {}", 
                     instrumentToken, interval.name(), e);
            return null;
        }
    }

    /**
     * Register an instrument with its token
     * 
     * @param tradingSymbol The instrument trading symbol
     * @param instrumentToken The instrument token from Kite
     * @param intervals The list of intervals to register (not used for caching)
     */
    public void registerInstrument(String tradingSymbol, long instrumentToken, List<String> intervals) {
        // Store only the instrument token and symbol mapping
        instrumentTokenToSymbolMap.put(instrumentToken, tradingSymbol);

        log.info("Registered instrument: {} with token: {}", tradingSymbol, instrumentToken);
    }

    /**
     * Clear all registered instruments from the cache
     */
    public void clearRegisteredInstruments() {
        instrumentTokenToSymbolMap.clear();
        log.info("Cleared all registered instruments");
    }

    /**
     * Process multiple ticks in bulk
     * 
     * @param ticks List of market data ticks
     * @return Number of successfully processed ticks
     */
    public int processTicks(List<DataTick> ticks) {
        int successCount = 0;
        for (DataTick tick : ticks) {
            if (processTick(tick)) {
                successCount++;
            }
        }
        return successCount;
    }
}
