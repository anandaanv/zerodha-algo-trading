package com.dtech.algo.runner.candle;

import com.dtech.algo.series.Interval;
import com.dtech.chartdata.model.OhlcBarDTO;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of the current (not-yet-completed) candlestick bar per symbol/interval.
 *
 * Updated on every incoming tick from KiteTicker. The cache holds ONE bar per symbol+interval,
 * representing the bar currently in progress. When the bar period closes, CandleCloseScheduler
 * reads the completed bar, persists it to DB, and the cache resets on the next tick.
 *
 * This is the only source of truth for the live (sub-minute) candle.
 * Completed candles come from the DB (populated by CandleCloseScheduler).
 *
 * Key format: "SYMBOL:IntervalName"  e.g. "TCS:OneMinute"
 */
@Component
@Slf4j
public class LiveCandleCache {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // symbol:interval → current partial candle
    private final ConcurrentHashMap<String, PartialCandle> cache = new ConcurrentHashMap<>();
    // symbol:interval → last completed candle (saved when bar rotates)
    private final ConcurrentHashMap<String, PartialCandle> lastCompleted = new ConcurrentHashMap<>();

    /**
     * Update the live cache with an incoming tick.
     * Called from BarSeriesHelper for each tracked interval.
     *
     * @param symbol   trading symbol (e.g. "TCS")
     * @param interval interval to update
     * @param tick     incoming market tick
     */
    public void updateTick(String symbol, Interval interval, DataTick tick) {
        if (tick.getLastTradedPrice() <= 0) return;

        Instant tickTime = tick.getLastTradedTime() != null
                ? tick.getLastTradedTime().toInstant()
                : (tick.getTickTimestamp() != null ? tick.getTickTimestamp().toInstant() : Instant.now());

        Instant barStart = calculateBarStart(tickTime, interval);
        String key = symbol + ":" + interval.name();

        cache.compute(key, (k, existing) -> {
            if (existing == null || !existing.getBarStart().equals(barStart)) {
                // Bar period changed — save old bar before replacing
                if (existing != null) {
                    lastCompleted.put(k, existing);
                }
                return new PartialCandle(symbol, interval, barStart, tick);
            }
            existing.update(tick);
            return existing;
        });
    }

    /**
     * Return the current live (partial) candle for the given symbol/interval,
     * or null if no ticks have been received yet.
     */
    public OhlcBarDTO getLiveCandle(String symbol, Interval interval) {
        PartialCandle c = cache.get(symbol + ":" + interval.name());
        return c != null ? c.toDto() : null;
    }

    /**
     * Return all live candles for a specific interval.
     * Used by CandleCloseScheduler to persist all bars at once.
     *
     * @return map of symbol → OhlcBarDTO
     */
    public Map<String, OhlcBarDTO> getAllLiveCandles(Interval interval) {
        Map<String, OhlcBarDTO> result = new HashMap<>();
        cache.forEach((key, candle) -> {
            if (key.endsWith(":" + interval.name())) {
                String symbol = key.substring(0, key.lastIndexOf(':'));
                result.put(symbol, candle.toDto());
            }
        });
        return result;
    }

    /**
     * Return the completed candle for a given bar start time, or null if not found.
     * Checks lastCompleted first (handles the case where a new tick already rotated the cache),
     * then falls back to the current cache entry if its barStart still matches.
     */
    public OhlcBarDTO getCompletedBar(String symbol, Interval interval, Instant expectedBarStart) {
        String key = symbol + ":" + interval.name();
        // Prefer the saved completed-bar snapshot (most reliable)
        PartialCandle completed = lastCompleted.get(key);
        if (completed != null && completed.getBarStart().equals(expectedBarStart)) {
            return completed.toDto();
        }
        // Fallback: current bar hasn't rotated yet (no tick in the first second)
        PartialCandle current = cache.get(key);
        if (current != null && current.getBarStart().equals(expectedBarStart)) {
            return current.toDto();
        }
        return null;
    }

    // ─────────────────────────── Bar time calculation ─────────────────────

    public Instant calculateBarStart(Instant tickTime, Interval interval) {
        ZonedDateTime zdt = tickTime.atZone(IST);
        return switch (interval) {
            case OneMinute    -> zdt.truncatedTo(ChronoUnit.MINUTES).toInstant();
            case ThreeMinute  -> {
                int m = zdt.getMinute();
                yield zdt.withMinute(m - (m % 3)).truncatedTo(ChronoUnit.MINUTES).toInstant();
            }
            case FiveMinute   -> {
                int m = zdt.getMinute();
                yield zdt.withMinute(m - (m % 5)).truncatedTo(ChronoUnit.MINUTES).toInstant();
            }
            case FifteenMinute -> {
                int m = zdt.getMinute();
                yield zdt.withMinute(m - (m % 15)).truncatedTo(ChronoUnit.MINUTES).toInstant();
            }
            case ThirtyMinute -> {
                int m = zdt.getMinute();
                yield zdt.withMinute(m - (m % 30)).truncatedTo(ChronoUnit.MINUTES).toInstant();
            }
            case OneHour      -> zdt.truncatedTo(ChronoUnit.HOURS).toInstant();
            case Day          -> zdt.truncatedTo(ChronoUnit.DAYS).toInstant();
            default           -> zdt.truncatedTo(ChronoUnit.MINUTES).toInstant();
        };
    }

    // ─────────────────────────── Inner type ───────────────────────────────

    @Data
    public static class PartialCandle {
        private final String symbol;
        private final Interval interval;
        private final Instant barStart;

        private double open;
        private double high;
        private double low;
        private double close;
        private double volume;
        // Cumulative day-volume at bar open — used to compute bar volume delta
        private double baseVolume;

        public PartialCandle(String symbol, Interval interval, Instant barStart, DataTick tick) {
            this.symbol     = symbol;
            this.interval   = interval;
            this.barStart   = barStart;
            double ltp      = tick.getLastTradedPrice();
            // First tick of bar: open = close = LTP; high/low initialised to LTP
            this.open       = ltp;
            this.high       = ltp;
            this.low        = ltp;
            this.close      = ltp;
            this.baseVolume = tick.getVolumeTradedToday();
            this.volume     = 0;
        }

        public synchronized void update(DataTick tick) {
            double ltp = tick.getLastTradedPrice();
            this.close = ltp;
            if (ltp > this.high)           this.high = ltp;
            if (ltp < this.low && ltp > 0) this.low  = ltp;
            // Bar volume = today's cumulative minus what it was at bar start
            double cumVol = tick.getVolumeTradedToday();
            if (cumVol >= this.baseVolume) this.volume = cumVol - this.baseVolume;
        }

        public OhlcBarDTO toDto() {
            return new OhlcBarDTO(barStart.getEpochSecond(), open, high, low, close, volume);
        }
    }
}
