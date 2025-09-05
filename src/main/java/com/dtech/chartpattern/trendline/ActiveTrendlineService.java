package com.dtech.chartpattern.trendline;

import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.chartpattern.config.ChartPatternProperties;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.controller.BarSeriesHelper;
import com.dtech.kitecon.data.Instrument;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes "active" support/resistance lines from ZigZag pivots and OHLC bars.
 * - Uses ATR-based tolerance (with pctBand fallback) for touch/break evaluation
 * - Scores include touches, recency boost, break penalties
 * - Adds significance for near-flat lines and for support-resistance pairs
 *   that are parallel (channel) or converging/diverging (wedge-like)
 * - No persistence; intended for visualization/debugging
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActiveTrendlineService {

    private final ZigZagService zigZagService;
    private final ChartPatternProperties props;
    private final BarSeriesHelper barSeriesHelper;

    @Data
    @Builder
    @AllArgsConstructor
    public static class ActiveTrendline {
        public enum Side { SUPPORT, RESISTANCE }

        private Side side;
        private long pivot1Seq;     // epoch seconds for first pivot
        private long pivot2Seq;     // epoch seconds for second pivot
        private double slope;       // price per second
        private double intercept;   // line y = slope * t + intercept (t in epoch seconds)
        private int touchCount;
        private int breakCount;
        private long lastTouchSeq;  // epoch seconds of last touch, -1 if none
        private double score;

        // Extra info for visualization/interpretation
        private boolean nearFlat;
        private String pairType; // PARALLEL | CONVERGING | DIVERGING | null
        private double angleDeg; // |atan(slope * secondsPerBar)| converted for readability
    }

    public List<ActiveTrendline> compute(String tradingSymbol, Instrument instrument, Interval interval) {
        try {
            IntervalBarSeries series = barSeriesHelper.getIntervalBarSeries(tradingSymbol, interval.name());
            if (series == null || series.isEmpty()) return Collections.emptyList();

            List<ZigZagPoint> pivots = zigZagService.getOrComputePivots(tradingSymbol, instrument, interval);
            if (pivots == null || pivots.size() < 3) return Collections.emptyList();

            // ATR for tolerance
            int atrLen = props.getZigzag().getAtrLength();
            double[] atr = computeAtrWilder(series, atrLen);

            // Defaults
            var tcfg = props.getTrendline();
            int lastN = Math.max(1, 10); // prompt default: 10 (show more recent)
            double pctBand = Math.max(1e-6, tcfg.getPctBand());
            double atrBandMult = Math.max(0.0, tcfg.getAtrBandMult());
            int recencyBars = Math.max(1, tcfg.getRecentEmphasisBars());
            double recencyWeight = Math.max(0.0, tcfg.getRecencyWeight());
            double flatAngleDeg = Math.max(0.0, tcfg.getFlatAngleDeg());
            double slopeSimilarityEps = Math.max(0.0, tcfg.getSlopeSimilarityEps());

            // Split pivots
            List<ZigZagPoint> highs = pivots.stream().filter(ZigZagPoint::isHigh).collect(Collectors.toList());
            List<ZigZagPoint> lows  = pivots.stream().filter(ZigZagPoint::isLow).collect(Collectors.toList());

            // Precompute bar end-times in epoch seconds for time-axis evaluation
            int endIdx = series.getEndIndex();
            long[] barTimesSec = new long[endIdx + 1];
            for (int i = 0; i <= endIdx; i++) {
                barTimesSec[i] = series.getBar(i).getEndTime().toEpochSecond();
            }

            List<ActiveTrendline> supports = computeSide(series, barTimesSec, lows, ActiveTrendline.Side.SUPPORT,
                    lastN, atr, pctBand, atrBandMult, recencyBars, recencyWeight, flatAngleDeg);
            List<ActiveTrendline> resistances = computeSide(series, barTimesSec, highs, ActiveTrendline.Side.RESISTANCE,
                    lastN, atr, pctBand, atrBandMult, recencyBars, recencyWeight, flatAngleDeg);

            // Join all lines (we will apply a proximity filter to keep recent/relevant ones)
            List<ActiveTrendline> all = new ArrayList<>(supports.size() + resistances.size());
            all.addAll(supports);
            all.addAll(resistances);

            // ATR-based proximity filter: discard lines whose starting/second point is too far from current price
            long tNow = series.getBar(endIdx).getEndTime().toEpochSecond();
            double lastClose = series.getBar(endIdx).getClosePrice().doubleValue();
            double lastAtr = (atr != null && endIdx >= 0 && endIdx < atr.length) ? atr[endIdx] : 0.0;
            double xAtr = 20 * computeProximityAtrMult(series, atr); // adaptive X (ATR multiples)

            List<ActiveTrendline> filtered;
            if (lastAtr > 0.0) {
                filtered = all.stream().filter(al -> {
                    double yStart = al.getSlope() * (double) al.getPivot1Seq() + al.getIntercept();
                    double ySecond = al.getSlope() * (double) al.getPivot2Seq() + al.getIntercept();
                    double yNow = al.getSlope() * (double) tNow + al.getIntercept();
                    double distStart = Math.abs(yStart - lastClose);
                    double distSecond = Math.abs(ySecond - lastClose);
                    double distNow = Math.abs(yNow - lastClose);

                    // Angle-aware tolerance scaling: allow farther distance for steeper lines
                    double angleScale = 1.0 + Math.min(2.0, Math.abs(al.getAngleDeg()) / 45.0); // up to 3x at ~90°
                    double threshold = xAtr * lastAtr * angleScale;

                    return Math.min(Math.min(distStart, distSecond), distNow) <= threshold;
                }).collect(Collectors.toList());
            } else {
                // Fallback: if ATR unavailable, skip filtering
                filtered = all;
            }

            // Suppress near-duplicate lines to reduce clutter
            List<ActiveTrendline> deduped = deduplicateSimilarLines(filtered, series, atr, slopeSimilarityEps, pctBand, atrBandMult);

            // Return many lines to visualize with their weightage (sorted by score)
            deduped.sort(Comparator.comparingDouble(ActiveTrendline::getScore).reversed());
            return deduped;
        } catch (Exception e) {
            log.warn("Active trendline computation failed for {}@{}: {}", tradingSymbol, interval, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private List<ActiveTrendline> computeSide(IntervalBarSeries series,
                                              long[] barTimesSec,
                                              List<ZigZagPoint> pivots,
                                              ActiveTrendline.Side side,
                                              int lastN,
                                              double[] atr,
                                              double pctBand,
                                              double atrBandMult,
                                              int recencyBars,
                                              double recencyWeight,
                                              double flatAngleDeg) {
        if (pivots.size() < 2) return Collections.emptyList();

        int n = pivots.size();
        int startSecond = Math.max(1, n - lastN);
        int end = series.getEndIndex();

        // seconds-per-bar (approx) for an angle hint; for display only
        double secPerBar = end > 0 ? Math.max(1, barTimesSec[end] - barTimesSec[Math.max(0, end - 1)]) : 60.0;

        List<ActiveTrendline> candidates = new ArrayList<>();
        for (int j = startSecond; j < n; j++) {
            ZigZagPoint p2 = pivots.get(j);
            for (int i = 0; i < j; i++) {
                ZigZagPoint p1 = pivots.get(i);

                long t1 = p1.getSequence();
                long t2 = p2.getSequence();
                if (t1 == t2) continue;

                // Use time axis (seconds) for slope/intercept
                double m = (p2.getValue() - p1.getValue()) / (double) (t2 - t1);
                double b = p1.getValue() - m * (double) t1;

                CandidateScore cs = scoreCandidate(series, barTimesSec, pivots, side, t1, t2, m, b, atr, pctBand, atrBandMult, recencyBars, recencyWeight);
                if (cs == null) continue;

                // Convert slope to per-bar equivalent for angle readability
                double mPerBar = m * secPerBar;
                double angleDeg = Math.toDegrees(Math.atan(mPerBar));
                boolean nearFlat = Math.abs(angleDeg) <= flatAngleDeg;

                double score = cs.score + (nearFlat ? 0.4 : 0.0); // slight boost for near-flat lines

                candidates.add(ActiveTrendline.builder()
                        .side(side)
                        .pivot1Seq(t1)
                        .pivot2Seq(t2)
                        .slope(m)
                        .intercept(b)
                        .touchCount(cs.touches)
                        .breakCount(cs.breaks)
                        .lastTouchSeq(cs.lastTouchSeq)
                        .score(score)
                        .nearFlat(nearFlat)
                        .pairType(null)
                        .angleDeg(Math.abs(angleDeg))
                        .build());
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(ActiveTrendline::getScore).reversed())
                .collect(Collectors.toList());
    }

    private static class CandidateScore {
        int touches;
        int breaks;
        long lastTouchSeq;
        double score;
    }

    private CandidateScore scoreCandidate(IntervalBarSeries series,
                                          long[] barTimesSec,
                                          List<ZigZagPoint> sidePivots,
                                          ActiveTrendline.Side side,
                                          long t1, long t2,
                                          double m, double b,
                                          double[] atr,
                                          double pctBand, double atrBandMult,
                                          int recencyBars, double recencyWeight) {
        int end = series.getEndIndex();

        // Angle-aware tolerance scaling for steep lines (display angle from per-bar conversion)
        double secPerBar = end > 0 ? Math.max(1, barTimesSec[end] - barTimesSec[Math.max(0, end - 1)]) : 60.0;
        double angleDeg = Math.toDegrees(Math.atan(m * secPerBar));
        double angleScale = 1.0 + Math.min(2.0, Math.abs(angleDeg) / 45.0);

        // Discard if a decisive break occurs before the second pivot (t < t2)
        for (int i = 0; i <= end; i++) {
            long t = barTimesSec[i];
            if (t >= t2) break;
            double lineY = m * (double) t + b;
            double close = series.getBar(i).getClosePrice().doubleValue();
            double tol = toleranceFor(i, close, atr, pctBand, atrBandMult) * angleScale;
            boolean broke = (side == ActiveTrendline.Side.SUPPORT) ? (close < lineY - tol)
                                                                    : (close > lineY + tol);
            if (broke) return null;
        }

        int touches = 0;
        int breaks = 0;
        long lastTouchSeq = -1L;

        // Count respecting pivots from second pivot onward (by sequence/time)
        long tEnd = barTimesSec[end];
        for (ZigZagPoint p : sidePivots) {
            long tp = p.getSequence();
            if (tp < t2 || tp > tEnd) continue;
            double lineY = m * (double) tp + b;
            double refPrice = p.getValue();
            // Map tp to nearest bar index for ATR-based tolerance
            int idx = indexForSeq(barTimesSec, tp);
            if (idx < 0) continue;
            double tol = toleranceFor(idx, refPrice, atr, pctBand, atrBandMult);
            boolean respects = (side == ActiveTrendline.Side.SUPPORT) ? (refPrice + tol >= lineY)
                                                                       : (refPrice - tol <= lineY);
            if (respects) {
                touches++;
                if (tp > lastTouchSeq) lastTouchSeq = tp;
            }
        }

        // Decisive breaks after second pivot (for penalties/labeling)
        boolean recentStrongBreak = false;
        int recentBreakWindow = Math.max(1, recencyBars / 5);
        for (int i = 0; i <= end; i++) {
            long t = barTimesSec[i];
            if (t < t2) continue;
            double lineY = m * (double) t + b;
            double close = series.getBar(i).getClosePrice().doubleValue();
            double tol = toleranceFor(i, close, atr, pctBand, atrBandMult);
            boolean broke = (side == ActiveTrendline.Side.SUPPORT) ? (close < lineY - tol)
                                                                    : (close > lineY + tol);
            if (broke) {
                breaks++;
                if (end - i <= recentBreakWindow) {
                    recentStrongBreak = true;
                }
            }
        }

        // Recency boost using bars since last touch (map lastTouchSeq to bar index)
        double recencyBoost = 0.0;
        if (lastTouchSeq > 0) {
            int lastTouchIdx = indexForSeq(barTimesSec, lastTouchSeq);
            if (lastTouchIdx >= 0) {
                int barsSince = Math.max(0, end - lastTouchIdx);
                double decay = 1.0 - Math.min(1.0, (double) barsSince / recencyBars);
                recencyBoost = recencyWeight * decay;
            }
        }

        double score = touches + recencyBoost - (0.6 * breaks);
        if (recentStrongBreak) score -= 2.0;

        CandidateScore out = new CandidateScore();
        out.touches = touches;
        out.breaks = breaks;
        out.lastTouchSeq = lastTouchSeq;
        out.score = score;
        return out;
    }

    private double toleranceFor(int barIndex, double price, double[] atr, double pctBand, double atrBandMult) {
        double pctTol = pctBand * price;
        double atrTol = (atr != null && barIndex >= 0 && barIndex < atr.length) ? atrBandMult * atr[barIndex] : 0.0;
        return Math.max(pctTol, atrTol);
    }

    private double[] computeAtrWilder(IntervalBarSeries series, int length) {
        int n = series.getBarCount();
        if (n == 0) return new double[0];

        double[] tr = new double[n];
        double[] atr = new double[n];

        for (int i = 0; i < n; i++) {
            double high = series.getBar(i).getHighPrice().doubleValue();
            double low = series.getBar(i).getLowPrice().doubleValue();
            double prevClose = (i == 0) ? series.getBar(i).getClosePrice().doubleValue() : series.getBar(i - 1).getClosePrice().doubleValue();
            double tr1 = high - low;
            double tr2 = Math.abs(high - prevClose);
            double tr3 = Math.abs(low - prevClose);
            tr[i] = Math.max(tr1, Math.max(tr2, tr3));

            if (i == 0) {
                atr[i] = tr[i];
            } else if (i < length) {
                atr[i] = ((atr[i - 1] * i) + tr[i]) / (i + 1);
            } else if (i == length) {
                double sum = 0.0;
                for (int k = i - length + 1; k <= i; k++) sum += tr[k];
                atr[i] = sum / length;
            } else {
                atr[i] = ((atr[i - 1] * (length - 1)) + tr[i]) / length;
            }
        }
        return atr;
    }

    /**
     * Compute adaptive ATR multiplier (X) for proximity filtering.
     * Target: keep threshold near a configured percent of price, so X = (targetPct * price) / ATR.
     * Clamped to [0.5, 10.0] to avoid extremes; falls back to 10.0 when ATR/price not available.
     */
    private double computeProximityAtrMult(IntervalBarSeries series, double[] atr) {
        int endIdx = series.getEndIndex();
        if (endIdx < 0) return 10.0;

        double lastClose = series.getBar(endIdx).getClosePrice().doubleValue();
        double lastAtr = (atr != null && endIdx >= 0 && endIdx < atr.length) ? atr[endIdx] : 0.0;
        if (lastClose <= 0.0 || lastAtr <= 0.0) return 10.0;

        double targetPct = props.getTrendline() != null ? props.getTrendline().getMaxProximityNowPct() : 0.01; // default 1%
        targetPct = Math.max(0.002, Math.min(0.02, targetPct)); // clamp between 0.2% and 2%

        double x = (targetPct * lastClose) / lastAtr;
        return Math.max(0.5, Math.min(10.0, x));
    }

    // Deduplicate near-overlapping lines (same side) by slope and endpoint similarity
    private List<ActiveTrendline> deduplicateSimilarLines(List<ActiveTrendline> lines,
                                                          IntervalBarSeries series,
                                                          double[] atr,
                                                          double slopeEps,
                                                          double pctBand,
                                                          double atrBandMult) {
        if (lines == null || lines.isEmpty()) return lines;

        int end = series.getEndIndex();
        long tNow = series.getBar(end).getEndTime().toEpochSecond();

        Map<ActiveTrendline.Side, List<ActiveTrendline>> bySide = lines.stream()
                .collect(Collectors.groupingBy(ActiveTrendline::getSide));

        List<ActiveTrendline> result = new ArrayList<>();
        for (Map.Entry<ActiveTrendline.Side, List<ActiveTrendline>> e : bySide.entrySet()) {
            List<ActiveTrendline> sideLines = new ArrayList<>(e.getValue());
            sideLines.sort(Comparator.comparingDouble(ActiveTrendline::getScore).reversed());

            List<ActiveTrendline> kept = new ArrayList<>();
            for (ActiveTrendline cand : sideLines) {
                boolean overlaps = false;
                for (ActiveTrendline keep : kept) {
                    if (areSimilar(cand, keep, series, tNow, atr, slopeEps, pctBand, atrBandMult)) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) {
                    kept.add(cand);
                }
            }
            result.addAll(kept);
        }
        return result;
    }

    private boolean areSimilar(ActiveTrendline a,
                               ActiveTrendline b,
                               IntervalBarSeries series,
                               long tNow,
                               double[] atr,
                               double slopeEps,
                               double pctBand,
                               double atrBandMult) {
        // 1) Slope similarity (both are price/second)
        if (Math.abs(a.getSlope() - b.getSlope()) > slopeEps) return false;

        // 2) Anchor proximity (map sequences to nearest indices to approximate "within ~5 bars")
        int a1 = indexForSeq(buildBarTimes(series), a.getPivot1Seq());
        int b1 = indexForSeq(buildBarTimes(series), b.getPivot1Seq());
        int a2 = indexForSeq(buildBarTimes(series), a.getPivot2Seq());
        int b2 = indexForSeq(buildBarTimes(series), b.getPivot2Seq());
        if (a1 >= 0 && b1 >= 0 && a2 >= 0 && b2 >= 0) {
            if (Math.abs(a1 - b1) <= 5 && Math.abs(a2 - b2) <= 5) return true;
        }

        // 3) Price proximity now
        int lastIdx = series.getEndIndex();
        double yA = a.getSlope() * (double) tNow + a.getIntercept();
        double yB = b.getSlope() * (double) tNow + b.getIntercept();
        double price = series.getBar(lastIdx).getClosePrice().doubleValue();
        double tol = toleranceFor(lastIdx, price, atr, pctBand, atrBandMult) * 1.5;
        return Math.abs(yA - yB) <= tol;
    }

    private int indexForSeq(long[] barTimesSec, long seq) {
        // binary search nearest index for given epoch seconds
        int lo = 0, hi = barTimesSec.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long tm = barTimesSec[mid];
            if (tm == seq) return mid;
            if (tm < seq) lo = mid + 1; else hi = mid - 1;
        }
        // lo is the first index greater than seq; pick the closer of lo and hi
        int cand1 = Math.min(Math.max(0, hi), barTimesSec.length - 1);
        int cand2 = Math.min(Math.max(0, lo), barTimesSec.length - 1);
        return Math.abs(barTimesSec[cand1] - seq) <= Math.abs(barTimesSec[cand2] - seq) ? cand1 : cand2;
    }

    private int indexForSeq(long[] barTimesSec, long seq, boolean requireExact) {
        int idx = indexForSeq(barTimesSec, seq);
        if (!requireExact) return idx;
        return barTimesSec[idx] == seq ? idx : -1;
    }

    private long[] buildBarTimes(IntervalBarSeries series) {
        int end = series.getEndIndex();
        long[] arr = new long[end + 1];
        for (int i = 0; i <= end; i++) {
            arr[i] = series.getBar(i).getEndTime().toEpochSecond();
        }
        return arr;
    }
}
