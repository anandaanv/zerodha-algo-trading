package com.dtech.kitecon.controller;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.runner.candle.DataTick;
import com.dtech.algo.runner.candle.LatestBarSeriesProvider;
import com.dtech.algo.runner.candle.LiveCandleCache;
import com.dtech.algo.runner.candle.MarketDataWebSocketService;
import com.dtech.algo.series.Exchange;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.InstrumentType;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.config.HistoricalDateLimit;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.DatabaseBatchUpdateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper for loading and updating bar series.
 *
 * Tick processing path (hot path, called on every tick):
 *   tick → LiveCandleCache.updateTick()  (O(1), no DB)
 *        → MarketDataWebSocketService.broadcastBar()  (live update to chart)
 *
 * The heavy loadBarSeries() call is only used for screeners/strategies,
 * NOT on every tick. This avoids the previous anti-pattern of reloading
 * full historical data on every incoming tick.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class BarSeriesHelper {

    private final HistoricalDateLimit historicalDateLimit;
    private final LatestBarSeriesProvider barSeriesLoader;
    private final DatabaseBatchUpdateService databaseBatchUpdateService;
    private final InstrumentRepository instrumentRepository;
    private final LiveCandleCache liveCandleCache;
    private final MarketDataWebSocketService webSocketService;

    // Tracked intervals for live cache updates
    private static final List<Interval> TRACKED_INTERVALS =
            List.of(Interval.OneMinute, Interval.FiveMinute, Interval.FifteenMinute, Interval.OneHour);

    // token → symbol cache
    private final Map<Long, String> instrumentTokenToSymbolMap = new ConcurrentHashMap<>();

    // ─────────────────────── Bar series loading (for screeners/strategies) ──

    public BarSeriesConfig createBarSeriesConfig(String symbol, String timeframe) {
        Interval interval = Interval.valueOf(timeframe);
        int duration = historicalDateLimit.getScreenerDuration("NSE", interval);
        return BarSeriesConfig.builder()
                .interval(interval)
                .exchange(Exchange.NSE)
                .instrument(symbol)
                .instrumentType(InstrumentType.EQ)
                .startDate(Instant.now().minus(duration, ChronoUnit.DAYS))
                .endDate(Instant.now())
                .name(symbol + "_" + timeframe)
                .build();
    }

    public IntervalBarSeries getIntervalBarSeries(String stock, String tf) {
        BarSeriesConfig config = createBarSeriesConfig(stock, tf);
        try {
            return barSeriesLoader.loadBarSeries(config);
        } catch (StrategyException e) {
            throw new RuntimeException(e);
        }
    }

    // ─────────────────────── Tick processing (hot path) ──────────────────

    /**
     * Process a single tick: update live cache + broadcast to WebSocket.
     * Does NOT call loadBarSeries() — that's reserved for screeners.
     */
    public boolean processTick(DataTick tick) {
        String symbol = instrumentTokenToSymbolMap.get(tick.getInstrumentToken());
        if (symbol == null) {
            // Lazy lookup from DB
            Instrument instrument = instrumentRepository.findById(tick.getInstrumentToken()).orElse(null);
            if (instrument == null) {
                log.warn("No instrument for token {}", tick.getInstrumentToken());
                return false;
            }
            symbol = instrument.getTradingsymbol();
            instrumentTokenToSymbolMap.put(tick.getInstrumentToken(), symbol);
        }

        for (Interval interval : TRACKED_INTERVALS) {
            try {
                liveCandleCache.updateTick(symbol, interval, tick);

                // Broadcast live (partial) bar update to WebSocket clients
                OhlcBarDTO liveBar = liveCandleCache.getLiveCandle(symbol, interval);
                if (liveBar != null) {
                    webSocketService.broadcastBar(symbol, interval.name(), liveBar, true);
                }
            } catch (Exception e) {
                log.warn("Error updating live cache for {} {}: {}", symbol, interval, e.getMessage());
            }
        }
        return true;
    }

    public boolean processTick(DataTick tick, Interval interval) {
        // Kept for backward compat — delegates to full processTick
        return processTick(tick);
    }

    public int processTicks(List<DataTick> ticks) {
        int success = 0;
        for (DataTick tick : ticks) {
            if (processTick(tick)) success++;
        }
        return success;
    }

    // ─────────────────────── Instrument registration ──────────────────────

    public void registerInstrument(String tradingSymbol, long instrumentToken, List<String> intervals) {
        instrumentTokenToSymbolMap.put(instrumentToken, tradingSymbol);
        log.info("Registered instrument: {} (token={})", tradingSymbol, instrumentToken);
    }

    public void clearRegisteredInstruments() {
        instrumentTokenToSymbolMap.clear();
    }

    // ─────────────────────── Config helpers ───────────────────────────────

    public BarSeriesConfig getConfigForInstrumentToken(long instrumentToken) {
        return getConfigForInstrumentToken(instrumentToken, Interval.OneMinute);
    }

    public BarSeriesConfig getConfigForInstrumentToken(long instrumentToken, Interval interval) {
        String tradingSymbol = instrumentTokenToSymbolMap.get(instrumentToken);
        if (tradingSymbol == null) {
            Instrument instrument = instrumentRepository.findById(instrumentToken).orElse(null);
            if (instrument == null) return null;
            tradingSymbol = instrument.getTradingsymbol();
            instrumentTokenToSymbolMap.put(instrumentToken, tradingSymbol);
        }
        return createBarSeriesConfig(tradingSymbol, interval.name());
    }

    public Double getLastPrice(Instrument instrument) throws StrategyException {
        BarSeriesConfig config = createBarSeriesConfig(instrument.getTradingsymbol(), Interval.Day.name());
        return barSeriesLoader.loadBarSeries(config).getLastBar().getClosePrice().doubleValue();
    }
}
