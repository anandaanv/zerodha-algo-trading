package com.dtech.kitecon.service.dataprovider;

import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Quote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Database-backed market data provider
 * Uses existing database tables for historical data
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseMarketDataProvider implements MarketDataProvider {

    private final BarSeriesLoader barSeriesLoader; // Existing database loader
    private final InstrumentRepository instrumentRepository;

    @Override
    public String getProviderName() {
        return "database";
    }

    @Override
    public boolean isAvailable() {
        return true; // Always available as it's the default
    }

    @Override
    public int getPriority() {
        return 1; // Lower priority (fallback)
    }

    @Override
    public IntervalBarSeries loadBarSeries(BarSeriesConfig config) throws Exception {
        log.debug("Loading bar series from database for {} - {}", config.getInstrument(), config.getInterval());
        return barSeriesLoader.loadBarSeries(config);
    }

    @Override
    public Quote getQuote(String symbol) throws Exception {
        throw new UnsupportedOperationException("Database provider does not support live quotes. Use Zerodha provider.");
    }

    @Override
    public Map<String, Quote> getQuotes(List<String> symbols) throws Exception {
        throw new UnsupportedOperationException("Database provider does not support live quotes. Use Zerodha provider.");
    }

    @Override
    public List<HistoricalData> getHistoricalData(
        Long instrumentToken,
        LocalDate from,
        LocalDate to,
        String interval,
        boolean continuous
    ) throws Exception {
        throw new UnsupportedOperationException("Database provider uses BarSeriesLoader. Use loadBarSeries() instead.");
    }
}
