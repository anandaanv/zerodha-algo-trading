package com.dtech.aitrader.v2.rules;

import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computes the three signature-driving enums — {@link MacroRegime}, {@link SrPosition},
 * {@link IndicatorConfluence} — over a {@link BarSeries} + pivot list. Pure function, no IO.
 *
 * <p>This is the heart of the context-attribution layer: same geometry firing in two different
 * probe states gets two different {@code context_signature}s, and the eval layer measures their
 * edge separately. If this probe is noisy / over-specific / under-specific, the entire pilot's
 * partitioning collapses — so the derivations are deliberately conservative and observable.
 */
@Component
@Slf4j
public class ContextProbe {

    // ── Indicator periods (pilot defaults; tune later)
    private static final int ADX_PERIOD = 14;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int EMA_FAST = 20;
    private static final int EMA_MID = 50;
    private static final int EMA_SLOW = 200;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;

    /** Lookback bars for the 6-month slope on daily. Approx 130 trading days = 6 months. */
    private static final int SLOPE_LOOKBACK = 130;

    /** Strong slope threshold (%). */
    private static final double STRONG_SLOPE_PCT = 5.0;

    /** Lookback bars for SR pivot clustering. */
    private static final int SR_PIVOT_LOOKBACK_BARS = 250;

    /** Minimum cluster size to call a level "major". */
    private static final int MAJOR_CLUSTER_MIN = 3;

    public ContextProbeResult compute(BarSeries series, List<MarketStructurePoint> pivots) {
        if (series == null || series.getBarCount() < SLOPE_LOOKBACK + 20) {
            return unknown();
        }
        try {
            int endIdx = series.getEndIndex();
            ClosePriceIndicator close = new ClosePriceIndicator(series);

            MacroRegime macro = computeMacroRegime(series, close, endIdx);
            ATRIndicator atr = new ATRIndicator(series, ATR_PERIOD);
            double atrNow = atr.getValue(endIdx).doubleValue();
            SrPosition sr = computeSrPosition(close.getValue(endIdx).doubleValue(), atrNow, pivots);
            IndicatorConfluence conf = computeIndicatorConfluence(series, close, endIdx);

            return ContextProbeResult.builder()
                    .macroRegime(macro)
                    .srPosition(sr)
                    .indicatorConfluence(conf)
                    .build();
        } catch (Exception e) {
            log.warn("[ctx-probe] compute failed: {}", e.getMessage());
            return unknown();
        }
    }

    private static ContextProbeResult unknown() {
        return ContextProbeResult.builder()
                .macroRegime(MacroRegime.UNKNOWN)
                .srPosition(SrPosition.UNKNOWN)
                .indicatorConfluence(IndicatorConfluence.UNKNOWN)
                .build();
    }

    // ──────────────────────────────────────────────────────────── macroRegime ──

    private MacroRegime computeMacroRegime(BarSeries series, ClosePriceIndicator close, int endIdx) {
        ADXIndicator adx = new ADXIndicator(series, ADX_PERIOD);
        double adxNow = adx.getValue(endIdx).doubleValue();

        EMAIndicator ema50 = new EMAIndicator(close, EMA_MID);
        EMAIndicator ema200 = new EMAIndicator(close, EMA_SLOW);
        double e50 = ema50.getValue(endIdx).doubleValue();
        double e200 = ema200.getValue(endIdx).doubleValue();
        int stackVote = (e50 > e200) ? +1 : (e50 < e200) ? -1 : 0;

        // 6-month slope
        int slopeIdx = Math.max(0, endIdx - SLOPE_LOOKBACK);
        double slopePct = pctChange(
                close.getValue(slopeIdx).doubleValue(),
                close.getValue(endIdx).doubleValue());
        int slopeVote = slopePct >= STRONG_SLOPE_PCT ? +1
                : slopePct <= -STRONG_SLOPE_PCT ? -1 : 0;

        boolean strongAdx = adxNow >= 25;
        boolean weakAdx = adxNow >= 15;
        boolean strongUp = strongAdx && stackVote == +1 && slopeVote == +1;
        boolean strongDown = strongAdx && stackVote == -1 && slopeVote == -1;
        if (strongUp) return MacroRegime.UPTREND_STRONG;
        if (strongDown) return MacroRegime.DOWNTREND_STRONG;

        // Sideways: low ADX AND mild slope
        if (adxNow < 15 && Math.abs(slopePct) < 3.0) return MacroRegime.SIDEWAYS;

        // Weak buckets — directional vote from stack + slope
        int weakDir = stackVote + slopeVote;
        if (weakDir > 0 && weakAdx) return MacroRegime.UPTREND_WEAK;
        if (weakDir < 0 && weakAdx) return MacroRegime.DOWNTREND_WEAK;
        return MacroRegime.SIDEWAYS;
    }

    // ─────────────────────────────────────────────────────────── srPosition ──

    private SrPosition computeSrPosition(double closeNow, double atrNow,
                                          List<MarketStructurePoint> pivots) {
        if (pivots == null || pivots.isEmpty() || atrNow <= 0) return SrPosition.UNKNOWN;

        // Last SR_PIVOT_LOOKBACK_BARS pivots — proxy: take last K pivots (cluster ~250 bars worth).
        // (Pivots are bar-sparse, so K=40 covers ~250 bars on daily.)
        int k = Math.min(pivots.size(), 40);
        List<MarketStructurePoint> recent = pivots.subList(pivots.size() - k, pivots.size());

        List<Double> highPrices = new ArrayList<>();
        List<Double> lowPrices = new ArrayList<>();
        for (MarketStructurePoint p : recent) {
            if (p.getPivotType() == PivotType.HIGH) highPrices.add(p.getPrice());
            else if (p.getPivotType() == PivotType.LOW) lowPrices.add(p.getPrice());
        }

        double clusterWidth = 2.0 * atrNow;
        List<Double> highClusterCentres = majorClusterCentres(highPrices, clusterWidth);
        List<Double> lowClusterCentres = majorClusterCentres(lowPrices, clusterWidth);

        // Nearest major-low cluster — AT_MAJOR_SUPPORT if within 1×ATR
        double nearestLowDist = nearestDistance(closeNow, lowClusterCentres);
        if (!Double.isNaN(nearestLowDist) && nearestLowDist <= atrNow) {
            return SrPosition.AT_MAJOR_SUPPORT;
        }
        // Nearest major-high cluster — AT_MAJOR_RESISTANCE if within 1×ATR
        double nearestHighDist = nearestDistance(closeNow, highClusterCentres);
        if (!Double.isNaN(nearestHighDist) && nearestHighDist <= atrNow) {
            return SrPosition.AT_MAJOR_RESISTANCE;
        }

        // EXTENDED — beyond every major cluster by ≥2×ATR
        double maxCentre = maxOrNan(combine(highClusterCentres, lowClusterCentres));
        double minCentre = minOrNan(combine(highClusterCentres, lowClusterCentres));
        if (!Double.isNaN(maxCentre) && closeNow > maxCentre + 2.0 * atrNow) {
            return SrPosition.EXTENDED_HIGH;
        }
        if (!Double.isNaN(minCentre) && closeNow < minCentre - 2.0 * atrNow) {
            return SrPosition.EXTENDED_LOW;
        }
        return SrPosition.MID_RANGE;
    }

    /** Simple 1D cluster: sort prices, group with width tolerance, return centres of size≥3 groups. */
    private List<Double> majorClusterCentres(List<Double> prices, double width) {
        if (prices == null || prices.isEmpty()) return Collections.emptyList();
        List<Double> sorted = new ArrayList<>(prices);
        Collections.sort(sorted);
        List<Double> centres = new ArrayList<>();
        List<Double> current = new ArrayList<>();
        current.add(sorted.get(0));
        for (int i = 1; i < sorted.size(); i++) {
            double v = sorted.get(i);
            double currentMean = current.stream().mapToDouble(Double::doubleValue).average().orElse(v);
            if (Math.abs(v - currentMean) <= width) {
                current.add(v);
            } else {
                if (current.size() >= MAJOR_CLUSTER_MIN) {
                    centres.add(current.stream().mapToDouble(Double::doubleValue).average().orElse(0));
                }
                current = new ArrayList<>();
                current.add(v);
            }
        }
        if (current.size() >= MAJOR_CLUSTER_MIN) {
            centres.add(current.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        }
        return centres;
    }

    private static double nearestDistance(double x, List<Double> centres) {
        double best = Double.NaN;
        for (Double c : centres) {
            double d = Math.abs(x - c);
            if (Double.isNaN(best) || d < best) best = d;
        }
        return best;
    }

    private static List<Double> combine(List<Double> a, List<Double> b) {
        List<Double> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    private static double maxOrNan(List<Double> xs) {
        return xs.isEmpty() ? Double.NaN : Collections.max(xs);
    }

    private static double minOrNan(List<Double> xs) {
        return xs.isEmpty() ? Double.NaN : Collections.min(xs);
    }

    // ───────────────────────────────────────────────────── indicatorConfluence ──

    private IndicatorConfluence computeIndicatorConfluence(BarSeries series, ClosePriceIndicator close,
                                                             int endIdx) {
        int bull = 0, bear = 0;

        // MACD vote
        MACDIndicator macd = new MACDIndicator(close, MACD_FAST, MACD_SLOW);
        EMAIndicator macdSignal = new EMAIndicator(macd, MACD_SIGNAL);
        double mLine = macd.getValue(endIdx).doubleValue();
        double mSig = macdSignal.getValue(endIdx).doubleValue();
        if (mLine > mSig && (mLine - mSig) > 0) bull++;
        else if (mLine < mSig && (mLine - mSig) < 0) bear++;

        // RSI vote
        RSIIndicator rsi = new RSIIndicator(close, RSI_PERIOD);
        double rsiV = rsi.getValue(endIdx).doubleValue();
        if (rsiV > 55) bull++;
        else if (rsiV < 45) bear++;

        // ADX direction vote
        ADXIndicator adx = new ADXIndicator(series, ADX_PERIOD);
        PlusDIIndicator plusDi = new PlusDIIndicator(series, ADX_PERIOD);
        MinusDIIndicator minusDi = new MinusDIIndicator(series, ADX_PERIOD);
        double adxV = adx.getValue(endIdx).doubleValue();
        double plus = plusDi.getValue(endIdx).doubleValue();
        double minus = minusDi.getValue(endIdx).doubleValue();
        if (adxV > 15) {
            if (plus > minus) bull++;
            else if (minus > plus) bear++;
        }

        // EMA-stack vote
        EMAIndicator e20 = new EMAIndicator(close, EMA_FAST);
        EMAIndicator e50 = new EMAIndicator(close, EMA_MID);
        EMAIndicator e200 = new EMAIndicator(close, EMA_SLOW);
        double v20 = e20.getValue(endIdx).doubleValue();
        double v50 = e50.getValue(endIdx).doubleValue();
        double v200 = e200.getValue(endIdx).doubleValue();
        if (v20 > v50 && v50 > v200) bull++;
        else if (v20 < v50 && v50 < v200) bear++;

        if (bull >= 3 && bear == 0) return IndicatorConfluence.BULL_HIGH;
        if (bear >= 3 && bull == 0) return IndicatorConfluence.BEAR_HIGH;
        if (bull >= 2 && bear <= 1) return IndicatorConfluence.BULL_MIXED;
        if (bear >= 2 && bull <= 1) return IndicatorConfluence.BEAR_MIXED;
        return IndicatorConfluence.NEUTRAL;
    }

    // ───────────────────────────────────────────────────────────── helpers ──

    private static double pctChange(double from, double to) {
        if (from <= 0) return 0.0;
        return ((to - from) / from) * 100.0;
    }

    @SuppressWarnings("unused")
    private static double v(Indicator<Num> ind, int idx) {
        return ind.getValue(idx).doubleValue();
    }
}
