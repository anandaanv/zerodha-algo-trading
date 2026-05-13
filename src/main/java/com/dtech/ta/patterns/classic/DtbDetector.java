package com.dtech.ta.patterns.classic;

import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.simulation.CandidatePivotZigZag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DtbDetector extracts DTB (Double Top / Double Bottom) pattern detection logic
 * for use in live detection services and scheduled scans.
 *
 * Detects patterns on a given BarSeries and returns DtbSignal records with
 * direction, neckline, measured-move target, retest bar index, and pivots.
 */
@Service
@Slf4j
public class DtbDetector {

    private static final double RETEST_TOLERANCE_PCT = 0.5;
    private static final int MAX_BARS_TO_BREAKOUT = 30;
    private static final int MAX_BARS_TO_RETEST = 30;

    /**
     * Detects DTB patterns on the latest portion of a bar series.
     * Only signals that complete within the lookback window (i.e. found entry + retest in recent bars)
     * are returned, to ensure freshness for live trading.
     *
     * @param series The full bar series (hourly)
     * @param lookbackWindow Number of recent bars to search for fresh patterns
     * @return Optional of DtbSignal if a fresh pattern is detected
     */
    public Optional<DtbSignal> detectLatest(BarSeries series, int lookbackWindow) {
        if (series.getBarCount() < 100) {
            return Optional.empty();
        }

        int endIndex = series.getEndIndex();
        int startSearchIndex = Math.max(0, endIndex - lookbackWindow);

        // Extract CPZZ pivots
        List<PivotPoint> pivots = runCpzz(series);
        if (pivots.size() < 4) {
            return Optional.empty();
        }

        // Try to find a fresh DTB retest in the recent lookback window
        // First, look for recent patterns that completed (neckline breakout + retest in window)

        // Check Double Top patterns (bearish)
        for (int i = 0; i < pivots.size() - 3; i++) {
            PivotPoint p0 = pivots.get(i);
            PivotPoint p1 = pivots.get(i + 1);
            PivotPoint p2 = pivots.get(i + 2);
            PivotPoint p3 = pivots.get(i + 3);

            // Pattern: High, Low, High, Low (DOUBLE TOP)
            if (p0.type() == PivotType.HIGH && p1.type() == PivotType.LOW &&
                p2.type() == PivotType.HIGH && p3.type() == PivotType.LOW) {

                // Neckline is p1 (the low between the two highs)
                double neckline = p1.price();
                double aPrice = p0.price();
                double cPrice = p2.price();

                // Check if pattern endpoint is within lookback (fresh)
                if (p3.barIndex() < startSearchIndex) {
                    continue; // Pattern too old
                }

                // Try to detect breakout + retest from p3 onwards
                Optional<Integer> retestBarOpt = findRetest(series, p3.barIndex(), neckline, false);
                if (retestBarOpt.isPresent()) {
                    int retestBar = retestBarOpt.get();
                    if (retestBar >= startSearchIndex && retestBar <= endIndex) {
                        // Fresh retest detected
                        double height = Math.abs(aPrice - neckline);
                        double target = neckline - height;
                        double atr = calculateAtrEstimate(series, p3.barIndex());

                        return Optional.of(new DtbSignal(
                            "SHORT",
                            neckline,
                            target,
                            retestBar,
                            atr,
                            List.of(p0, p1, p2, p3),
                            series.getBar(retestBar).getEndTime()
                        ));
                    }
                }
            }
        }

        // Check Double Bottom patterns (bullish)
        for (int i = 0; i < pivots.size() - 3; i++) {
            PivotPoint p0 = pivots.get(i);
            PivotPoint p1 = pivots.get(i + 1);
            PivotPoint p2 = pivots.get(i + 2);
            PivotPoint p3 = pivots.get(i + 3);

            // Pattern: Low, High, Low, High (DOUBLE BOTTOM)
            if (p0.type() == PivotType.LOW && p1.type() == PivotType.HIGH &&
                p2.type() == PivotType.LOW && p3.type() == PivotType.HIGH) {

                // Neckline is p1 (the high between the two lows)
                double neckline = p1.price();
                double aPrice = p0.price();
                double cPrice = p2.price();

                // Check if pattern endpoint is within lookback (fresh)
                if (p3.barIndex() < startSearchIndex) {
                    continue; // Pattern too old
                }

                // Try to detect breakout + retest from p3 onwards
                Optional<Integer> retestBarOpt = findRetest(series, p3.barIndex(), neckline, true);
                if (retestBarOpt.isPresent()) {
                    int retestBar = retestBarOpt.get();
                    if (retestBar >= startSearchIndex && retestBar <= endIndex) {
                        // Fresh retest detected
                        double height = Math.abs(aPrice - neckline);
                        double target = neckline + height;
                        double atr = calculateAtrEstimate(series, p3.barIndex());

                        return Optional.of(new DtbSignal(
                            "LONG",
                            neckline,
                            target,
                            retestBar,
                            atr,
                            List.of(p0, p1, p2, p3),
                            series.getBar(retestBar).getEndTime()
                        ));
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Finds the first breakout + retest after a pattern endpoint.
     *
     * @param series The bar series
     * @param patternEndBar Index of the pattern's last pivot
     * @param neckline The neckline price
     * @param bullish True for LONG (breakout above neckline), false for SHORT (below)
     * @return Optional of retest bar index if found
     */
    private Optional<Integer> findRetest(BarSeries series, int patternEndBar, double neckline, boolean bullish) {
        int endIndex = series.getEndIndex();

        // First find breakout
        int breakoutBar = -1;
        int maxBreakoutSearch = Math.min(patternEndBar + MAX_BARS_TO_BREAKOUT, endIndex);
        for (int i = patternEndBar + 1; i <= maxBreakoutSearch; i++) {
            double closePrice = series.getBar(i).getClosePrice().doubleValue();
            if ((bullish && closePrice > neckline) || (!bullish && closePrice < neckline)) {
                breakoutBar = i;
                break;
            }
        }

        if (breakoutBar < 0) {
            return Optional.empty(); // No breakout
        }

        // Then find retest
        double tolerance = neckline * RETEST_TOLERANCE_PCT / 100.0;
        int maxRetestSearch = Math.min(breakoutBar + MAX_BARS_TO_RETEST, endIndex);
        for (int i = breakoutBar + 1; i <= maxRetestSearch; i++) {
            Bar bar = series.getBar(i);
            double low = bar.getLowPrice().doubleValue();
            double high = bar.getHighPrice().doubleValue();

            if (low <= neckline + tolerance && high >= neckline - tolerance) {
                return Optional.of(i);
            }
        }

        return Optional.empty(); // No retest
    }

    private double calculateAtrEstimate(BarSeries series, int barIndex) {
        // Estimate ATR over last 14 bars
        int lookback = Math.min(14, barIndex + 1);
        double sumTr = 0;

        for (int i = Math.max(0, barIndex - lookback + 1); i <= barIndex; i++) {
            Bar current = series.getBar(i);
            double tr;

            if (i == 0) {
                tr = current.getHighPrice().doubleValue() - current.getLowPrice().doubleValue();
            } else {
                Bar prev = series.getBar(i - 1);
                double prevClose = prev.getClosePrice().doubleValue();

                double hl = current.getHighPrice().doubleValue() - current.getLowPrice().doubleValue();
                double hc = Math.abs(current.getHighPrice().doubleValue() - prevClose);
                double lc = Math.abs(current.getLowPrice().doubleValue() - prevClose);

                tr = Math.max(hl, Math.max(hc, lc));
            }

            sumTr += tr;
        }

        return sumTr / lookback;
    }

    private List<PivotPoint> runCpzz(BarSeries series) {
        ZigZagParams params = ZigZagParams.ofDefaults(
            14, 1.0, 0.005, 1.0, 1, false, 1.0, 14, ZigZagParams.Mode.BACKTEST);
        CandidatePivotZigZag cpzz = new CandidatePivotZigZag(params);

        for (int i = 0; i < series.getBarCount(); i++) {
            cpzz.processBar(series, i);
        }

        List<PivotPoint> result = new ArrayList<>();
        for (ZigZagPoint zp : cpzz.getConfirmedPivots()) {
            int barIndex = findBarByTimestamp(series, zp.getTimestamp());
            if (barIndex < 0) continue;

            PivotType type = zp.isHigh() ? PivotType.HIGH : PivotType.LOW;
            result.add(new PivotPoint(barIndex, zp.getTimestamp(), zp.getValue(), type));
        }

        result.sort(Comparator.comparingInt(PivotPoint::barIndex));
        return result;
    }

    private int findBarByTimestamp(BarSeries series, Instant ts) {
        int lo = series.getBeginIndex(), hi = series.getEndIndex();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = series.getBar(mid).getEndTime().compareTo(ts);
            if (cmp == 0) return mid;
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    /**
     * Record representing a detected DTB signal.
     */
    public record DtbSignal(
        String direction,               // LONG | SHORT
        Double neckline,
        Double measuredMoveTarget,
        Integer retestBarIndex,
        Double atr,
        List<PivotPoint> patternPivots,
        Instant signalTime
    ) {
    }

    /**
     * Record representing a pivot point in the pattern.
     */
    public record PivotPoint(
        Integer barIndex,
        Instant time,
        Double price,
        PivotType type
    ) {
    }

    /**
     * Enum for pivot type.
     */
    public enum PivotType {
        HIGH, LOW
    }
}
