package com.dtech.kitecon.simulation;

import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.elliott.CandlePatternRecognizer;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Incremental ZigZag that processes one bar at a time, maintaining internal state.
 * Eliminates the need to recompute ZigZag from scratch on each simulation step.
 *
 * Usage:
 *   IncrementalZigZag zz = new IncrementalZigZag(params);
 *   zz.initialize(series, upToBarIndex);  // process initial bars
 *   // On each new bar:
 *   boolean newPivot = zz.processBar(bar, barIndex);
 *   List<ZigZagPoint> pivots = zz.getPivots();
 */
@Slf4j
public class IncrementalZigZag {

    /** Default search window around a pivot bar when validating against candle patterns. */
    public static final int DEFAULT_VALIDATION_OFFSET_MIN = -2;
    public static final int DEFAULT_VALIDATION_OFFSET_MAX = 3;

    private enum Dir { UP, DOWN, NONE }

    private final ZigZagParams params;

    // ZigZag state
    private Dir dir = Dir.NONE;
    private int lastExtIdx = 0;
    private double lastExtPrice;
    private double currExtPriceHigh;
    private double currExtPriceLow;
    private int currExtIdx = 0;
    private Instant currExtHighTimestamp;
    private Instant currExtLowTimestamp;
    private final List<ZigZagPoint> pivots = new ArrayList<>();

    // ATR state (Wilder's smoothing)
    private double atr;
    private double prevClose;
    private int barCount = 0;
    private final int atrLength;

    // Relative volatility state (EMA of TR/Close)
    private double rvol;
    private final double rvolAlpha;

    // Track how many bars processed
    private int processedBars = 0;

    private final CandlePatternRecognizer recognizer = new CandlePatternRecognizer();

    public IncrementalZigZag(ZigZagParams params) {
        this.params = params;
        this.atrLength = params.getAtrLength();
        this.rvolAlpha = 2.0 / (params.getRvolWindow() + 1.0);
    }

    /**
     * Initialize from a BarSeries up to a given bar index.
     * Processes bars 0..upToIndex to build initial state.
     */
    public void initialize(BarSeries series, int upToIndex) {
        pivots.clear();
        dir = Dir.NONE;
        barCount = 0;
        processedBars = 0;

        int limit = Math.min(upToIndex + 1, series.getBarCount());
        for (int i = 0; i < limit; i++) {
            processBar(series.getBar(i), i);
        }
    }

    /**
     * Process a single new bar. Returns true if a new pivot was confirmed.
     */
    public boolean processBar(Bar bar, int barIndex) {
        double high = bar.getHighPrice().doubleValue();
        double low = bar.getLowPrice().doubleValue();
        double close = bar.getClosePrice().doubleValue();
        Instant timestamp = bar.getEndTime();

        // Update ATR
        double tr;
        if (barCount == 0) {
            tr = high - low;
            atr = tr;
            prevClose = close;
            lastExtPrice = close;
            currExtPriceHigh = high;
            currExtPriceLow = low;
            currExtIdx = barIndex;
            currExtHighTimestamp = timestamp;
            currExtLowTimestamp = timestamp;
            lastExtIdx = barIndex;
            rvol = close > 0 ? tr / close : 0;
            barCount++;
            processedBars++;
            return false;
        }

        double tr1 = high - low;
        double tr2 = Math.abs(high - prevClose);
        double tr3 = Math.abs(low - prevClose);
        tr = Math.max(tr1, Math.max(tr2, tr3));

        if (barCount < atrLength) {
            atr = ((atr * barCount) + tr) / (barCount + 1);
        } else if (barCount == atrLength) {
            // This is approximate — we don't have the full TR history for exact SMA seed
            atr = ((atr * (atrLength - 1)) + tr) / atrLength;
        } else {
            atr = ((atr * (atrLength - 1)) + tr) / atrLength;
        }

        // Update rvol
        double rv = close > 0 ? tr / close : 0;
        rvol = rvolAlpha * rv + (1 - rvolAlpha) * rvol;

        prevClose = close;
        barCount++;
        processedBars++;

        boolean newPivot = false;

        if (dir == Dir.NONE) {
            double move = close - lastExtPrice;
            double effectivePct = params.isDynamicPctEnabled() ? params.getVolMult() * rvol : params.getPctMin();
            double thr = Math.max(effectivePct * lastExtPrice, params.getAtrMult() * atr);
            if (Math.abs(move) >= thr) {
                dir = move > 0 ? Dir.UP : Dir.DOWN;
                currExtPriceHigh = high;
                currExtPriceLow = low;
                currExtIdx = barIndex;
                currExtHighTimestamp = timestamp;
                currExtLowTimestamp = timestamp;
            } else {
                if (high >= currExtPriceHigh) { currExtPriceHigh = high; currExtHighTimestamp = timestamp; }
                if (low <= currExtPriceLow) { currExtPriceLow = low; currExtLowTimestamp = timestamp; }
                currExtIdx = (high >= currExtPriceHigh) ? barIndex : (low <= currExtPriceLow ? barIndex : currExtIdx);
            }
        } else if (dir == Dir.UP) {
            if (high >= currExtPriceHigh) {
                currExtPriceHigh = high;
                currExtIdx = barIndex;
                currExtHighTimestamp = timestamp;
            }
            double reversalMove = currExtPriceHigh - low;
            double effectivePct = params.isDynamicPctEnabled() ? params.getVolMult() * rvol : params.getPctMin();
            double baseThr = Math.max(effectivePct * currExtPriceHigh, params.getAtrMult() * atr);
            double revThr = baseThr * params.getHysteresis();
            boolean spaced = (barIndex - lastExtIdx) >= params.getMinBarsBetweenPivots();
            if (reversalMove >= revThr && spaced) {
                pivots.add(buildPoint(currExtIdx, currExtPriceHigh, ZigZagPoint.Type.HIGH, currExtHighTimestamp));
                lastExtIdx = currExtIdx;
                lastExtPrice = currExtPriceHigh;
                dir = Dir.DOWN;
                currExtPriceLow = low;
                currExtIdx = barIndex;
                currExtLowTimestamp = timestamp;
                newPivot = true;
            }
        } else { // DOWN
            if (low <= currExtPriceLow) {
                currExtPriceLow = low;
                currExtIdx = barIndex;
                currExtLowTimestamp = timestamp;
            }
            double reversalMove = high - currExtPriceLow;
            double effectivePct = params.isDynamicPctEnabled() ? params.getVolMult() * rvol : params.getPctMin();
            double baseThr = Math.max(effectivePct * Math.max(1e-9, currExtPriceLow), params.getAtrMult() * atr);
            double revThr = baseThr * params.getHysteresis();
            boolean spaced = (barIndex - lastExtIdx) >= params.getMinBarsBetweenPivots();
            if (reversalMove >= revThr && spaced) {
                pivots.add(buildPoint(currExtIdx, currExtPriceLow, ZigZagPoint.Type.LOW, currExtLowTimestamp));
                lastExtIdx = currExtIdx;
                lastExtPrice = currExtPriceLow;
                dir = Dir.UP;
                currExtPriceHigh = high;
                currExtIdx = barIndex;
                currExtHighTimestamp = timestamp;
                newPivot = true;
            }
        }

        return newPivot;
    }

    /**
     * Get the confirmed pivots plus a trailing extreme (matching BACKTEST mode).
     */
    public List<ZigZagPoint> getPivotsWithTrailingExtreme(Bar currentBar) {
        List<ZigZagPoint> result = new ArrayList<>(pivots);
        if (!result.isEmpty()) {
            ZigZagPoint last = result.get(result.size() - 1);
            if (last.getType() == ZigZagPoint.Type.HIGH) {
                result.add(buildPoint(currExtIdx, currExtPriceLow, ZigZagPoint.Type.LOW,
                        currExtLowTimestamp != null ? currExtLowTimestamp : currentBar.getEndTime()));
            } else {
                result.add(buildPoint(currExtIdx, currExtPriceHigh, ZigZagPoint.Type.HIGH,
                        currExtHighTimestamp != null ? currExtHighTimestamp : currentBar.getEndTime()));
            }
        }
        return result;
    }

    /**
     * Get only the confirmed pivots (no trailing extreme).
     */
    public List<ZigZagPoint> getConfirmedPivots() {
        return new ArrayList<>(pivots);
    }

    /**
     * Whether a new pivot was confirmed since the last call to this method.
     */
    public boolean hasNewPivot() {
        return !pivots.isEmpty();
    }

    public int getPivotCount() {
        return pivots.size();
    }

    public int getProcessedBars() {
        return processedBars;
    }

    public double getCurrentAtr() {
        return atr;
    }

    /**
     * Default-window version: searches bars [pivotBar + DEFAULT_VALIDATION_OFFSET_MIN,
     * pivotBar + DEFAULT_VALIDATION_OFFSET_MAX] around each pivot for a candle pattern.
     *
     * Why a window: ZigZag's "pivot bar" is the bar holding the extreme price. The
     * actual reversal candle (engulfing / hammer / etc.) often forms 1–3 bars away
     * because hysteresis / spacing rules delay confirmation. A six-bar window
     * (-2..+3) catches ~94% of RELIANCE 1h pivots vs ~60% on the pivot bar alone.
     */
    public List<ZigZagPoint> validatePivotsAgainstPatterns(BarSeries series) {
        return validatePivotsAgainstPatterns(series, DEFAULT_VALIDATION_OFFSET_MIN, DEFAULT_VALIDATION_OFFSET_MAX);
    }

    /**
     * Configurable-window version. For each confirmed pivot, runs CandlePatternRecognizer
     * on every bar in [pivotIdx + offsetMin, pivotIdx + offsetMax]. If no offset matches,
     * the pivot is treated as truly uncaught and a WARN is logged.
     *
     * Returns the list of uncaught pivots — feed these into test fixtures to discover
     * patterns we should add to the recognizer.
     */
    public List<ZigZagPoint> validatePivotsAgainstPatterns(BarSeries series, int offsetMin, int offsetMax) {
        List<ZigZagPoint> uncaught = new ArrayList<>();
        if (series == null) return uncaught;
        int total = pivots.size();
        for (ZigZagPoint p : pivots) {
            int idx = p.getBarIndex();
            if (idx < 0 || idx >= series.getBarCount()) continue;
            CandlePatternRecognizer.Direction needed = (p.getType() == ZigZagPoint.Type.HIGH)
                    ? CandlePatternRecognizer.Direction.BEARISH
                    : CandlePatternRecognizer.Direction.BULLISH;
            boolean hit = false;
            for (int off = offsetMin; off <= offsetMax; off++) {
                int j = idx + off;
                if (j < 0 || j >= series.getBarCount()) continue;
                Optional<CandlePatternRecognizer.PatternResult> r = recognizer.detectAt(series, j, needed);
                if (r.isPresent()) { hit = true; break; }
            }
            if (!hit) {
                uncaught.add(p);
                Bar bar = series.getBar(idx);
                log.warn("[PivotMiss] bar={} ts={} type={} pivotPrice={} O={} H={} L={} C={} (window=[{},{}])",
                        idx, p.getTimestamp(), p.getType(), p.getValue(),
                        bar.getOpenPrice().doubleValue(),
                        bar.getHighPrice().doubleValue(),
                        bar.getLowPrice().doubleValue(),
                        bar.getClosePrice().doubleValue(),
                        offsetMin, offsetMax);
            }
        }
        log.info("[PivotValidate] total={} uncaught={} matched={} (window=[{},{}])",
                total, uncaught.size(), total - uncaught.size(), offsetMin, offsetMax);
        return uncaught;
    }

    private ZigZagPoint buildPoint(int idx, double price, ZigZagPoint.Type type, Instant timestamp) {
        return ZigZagPoint.builder()
                .type(type)
                .value(price)
                .timestamp(timestamp)
                .sequence(timestamp.getEpochSecond())
                .barIndex(idx)
                .atrAtPivot(atr)
                .build();
    }
}
