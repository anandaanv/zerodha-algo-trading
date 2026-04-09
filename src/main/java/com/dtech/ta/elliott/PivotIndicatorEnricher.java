package com.dtech.ta.elliott;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.StochasticOscillatorDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Layer 4: Enriches ZigZag pivots with all TA indicator values at each bar index.
 *
 * This is the foundation for all Elliott Wave and pattern analysis.
 * All indicators are computed once on the full BarSeries, then values are
 * looked up at each pivot's bar index — O(1) per pivot.
 */
@Service
public class PivotIndicatorEnricher {

    private static final int BOLLINGER_PERIOD = 20;
    private static final int BOLLINGER_STD_DEVS = 2;
    private static final int RSI_PERIOD = 14;
    private static final int STOCH_PERIOD = 14;
    private static final int STOCH_SIGNAL = 3;
    private static final int ADX_PERIOD = 14;
    private static final int EWO_SHORT = 5;
    private static final int EWO_LONG = 35;
    private static final int MACD_SHORT = 12;
    private static final int MACD_LONG = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int VOLUME_AVG_PERIOD = 20;
    private static final int BOLLINGER_SQUEEZE_LOOKBACK = 20;

    /**
     * Enrich a list of ZigZag pivots with indicator values from the given series.
     *
     * @param zigzagPoints  raw ZigZag pivots (oldest first)
     * @param structureData market structure labels per pivot (must be same timeframe)
     * @param series        full BarSeries for the same timeframe
     * @return enriched pivots with all indicator values filled in
     */
    public List<EnrichedPivot> enrich(
            List<ZigZagPoint> zigzagPoints,
            MarketStructureData structureData,
            BarSeries series) {

        if (zigzagPoints == null || zigzagPoints.isEmpty()) return List.of();

        // Build a lookup from timestamp → structure label for O(1) access
        Map<java.time.Instant, MarketStructurePoint.StructureLabel> labelMap =
                structureData.getSwingPoints().stream()
                        .collect(Collectors.toMap(
                                MarketStructurePoint::getTimestamp,
                                MarketStructurePoint::getStructureLabel,
                                (a, b) -> a));

        // ── Compute all indicators once ──────────────────────────────────────
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        // MACD (12, 26, 9)
        MACDIndicator macd       = new MACDIndicator(close, MACD_SHORT, MACD_LONG);
        EMAIndicator  macdSignal = new EMAIndicator(macd, MACD_SIGNAL);

        // EWO — Elliott Wave Oscillator = MACD(5, 35)
        MACDIndicator ewo = new MACDIndicator(close, EWO_SHORT, EWO_LONG);

        // RSI
        RSIIndicator rsi = new RSIIndicator(close, RSI_PERIOD);

        // Stochastic
        StochasticOscillatorKIndicator stochK = new StochasticOscillatorKIndicator(series, STOCH_PERIOD);
        StochasticOscillatorDIndicator stochD = new StochasticOscillatorDIndicator(stochK);

        // Bollinger Bands
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(
                new EMAIndicator(close, BOLLINGER_PERIOD));
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(close, BOLLINGER_PERIOD);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev);

        // ADX / DMI
        ADXIndicator    adxIndicator  = new ADXIndicator(series, ADX_PERIOD);
        PlusDIIndicator plusDI        = new PlusDIIndicator(series, ADX_PERIOD);
        MinusDIIndicator minusDI      = new MinusDIIndicator(series, ADX_PERIOD);

        // OBV
        OnBalanceVolumeIndicator obv = new OnBalanceVolumeIndicator(series);

        // EMA stack
        EMAIndicator ema20  = new EMAIndicator(close, 20);
        EMAIndicator ema50  = new EMAIndicator(close, 50);
        EMAIndicator ema200 = new EMAIndicator(close, 200);

        // Volume average for relative volume
        EMAIndicator volAvg = new EMAIndicator(volume, VOLUME_AVG_PERIOD);

        // ── Compute min bandwidth over lookback (for squeeze detection) ───────
        int totalBars = series.getBarCount();
        double[] bandWidths = new double[totalBars];
        for (int i = 0; i < totalBars; i++) {
            double mid = safeDouble(bbMiddle.getValue(i));
            double up  = safeDouble(bbUpper.getValue(i));
            double lo  = safeDouble(bbLower.getValue(i));
            bandWidths[i] = mid > 0 ? (up - lo) / mid : 0;
        }

        // Stoch-RSI: over 14 bars of RSI, compute (rsi - min14) / (max14 - min14), then K=SMA3, D=SMA3(K)
        double[] rsiValues = new double[totalBars];
        for (int i = 0; i < totalBars; i++) {
            rsiValues[i] = safeDouble(rsi.getValue(i));
        }
        double[] stochRsiRaw = new double[totalBars];
        for (int i = 0; i < totalBars; i++) {
            int start = Math.max(0, i - 13);
            double minRsi = rsiValues[start];
            double maxRsi = rsiValues[start];
            for (int j = start + 1; j <= i; j++) {
                if (rsiValues[j] < minRsi) minRsi = rsiValues[j];
                if (rsiValues[j] > maxRsi) maxRsi = rsiValues[j];
            }
            double range = maxRsi - minRsi;
            stochRsiRaw[i] = range > 0 ? (rsiValues[i] - minRsi) / range : 0.0;
        }
        // K = 3-period SMA of stochRsiRaw
        double[] stochRsiK = new double[totalBars];
        for (int i = 0; i < totalBars; i++) {
            if (i < 2) {
                stochRsiK[i] = stochRsiRaw[i];
            } else {
                stochRsiK[i] = (stochRsiRaw[i] + stochRsiRaw[i-1] + stochRsiRaw[i-2]) / 3.0;
            }
        }
        // D = 3-period SMA of K
        double[] stochRsiD = new double[totalBars];
        for (int i = 0; i < totalBars; i++) {
            if (i < 2) {
                stochRsiD[i] = stochRsiK[i];
            } else {
                stochRsiD[i] = (stochRsiK[i] + stochRsiK[i-1] + stochRsiK[i-2]) / 3.0;
            }
        }

        // MACD histogram amplitudes (for W4 contraction detection)
        double[] macdAmplitudes = new double[totalBars];
        for (int i = 0; i < totalBars; i++) {
            double h = safeDouble(macd.getValue(i)) - safeDouble(macdSignal.getValue(i));
            macdAmplitudes[i] = Math.abs(h);
        }

        // Build timestamp → bar index map for correct per-pivot indicator lookup.
        // ZigZagPoint.barIndex is always 0 (Lombok builder bypasses the private setSequence()),
        // so we resolve the correct bar position by matching timestamps instead.
        Map<java.time.Instant, Integer> tsToBarIdx = new java.util.HashMap<>(totalBars * 2);
        for (int i = 0; i < totalBars; i++) {
            tsToBarIdx.put(series.getBar(i).getEndTime(), i);
        }

        // ── Enrich each pivot ─────────────────────────────────────────────────
        List<EnrichedPivot> enriched = new ArrayList<>();

        for (ZigZagPoint zp : zigzagPoints) {
            Integer foundIdx = tsToBarIdx.get(zp.getTimestamp());
            if (foundIdx == null) continue;  // pivot timestamp not in series — stale snapshot
            int idx = foundIdx;
            if (idx < 0 || idx >= totalBars) continue;

            double price    = zp.getValue();
            double upper    = safeDouble(bbUpper.getValue(idx));
            double middle   = safeDouble(bbMiddle.getValue(idx));
            double lower    = safeDouble(bbLower.getValue(idx));
            double bw       = middle > 0 ? (upper - lower) / middle : 0;
            double pctB     = (upper - lower) > 0 ? (price - lower) / (upper - lower) : 0.5;
            double macdVal  = safeDouble(macd.getValue(idx));
            double sigVal   = safeDouble(macdSignal.getValue(idx));
            double histVal  = macdVal - sigVal;
            double amplitude = Math.abs(histVal);
            double peakAmplitude = peakAmplitude(macdAmplitudes, idx, 20);
            double amplitudeRatio = peakAmplitude > 0 ? amplitude / peakAmplitude : 0.0;
            double e20      = safeDouble(ema20.getValue(idx));
            double e50      = safeDouble(ema50.getValue(idx));
            double e200     = safeDouble(ema200.getValue(idx));
            double vol      = safeDouble(volume.getValue(idx));
            double vAvg     = safeDouble(volAvg.getValue(idx));

            // Bollinger squeeze: current bandwidth at its lowest in BOLLINGER_SQUEEZE_LOOKBACK bars
            boolean squeeze = isSqueeze(bandWidths, idx, BOLLINGER_SQUEEZE_LOOKBACK);

            // EMA stack order
            String emaStack = emaStackOrder(price, e20, e50, e200);

            MarketStructurePoint.StructureLabel label = labelMap.getOrDefault(
                    zp.getTimestamp(), MarketStructurePoint.StructureLabel.FIRST);

            enriched.add(EnrichedPivot.builder()
                    .type(zp.getType())
                    .timestamp(zp.getTimestamp())
                    .barIndex(idx)
                    .price(price)
                    .atrAtPivot(zp.getAtrAtPivot())
                    .retracementPct(zp.getRetracementPct())
                    .extensionPct(zp.getExtensionPct())
                    .structureLabel(label)
                    .macd(macdVal)
                    .macdSignal(sigVal)
                    .macdHistogram(histVal)
                    .macdHistogramAmplitude(amplitude)
                    .macdAmplitudeRatio(amplitudeRatio)
                    .macdNearZero(amplitudeRatio < 0.20)
                    .ewo(safeDouble(ewo.getValue(idx)))
                    .rsi(safeDouble(rsi.getValue(idx)))
                    .stochK(safeDouble(stochK.getValue(idx)))
                    .stochD(safeDouble(stochD.getValue(idx)))
                    .bollingerUpper(upper)
                    .bollingerMiddle(middle)
                    .bollingerLower(lower)
                    .bollingerPctB(pctB)
                    .bollingerWidth(bw)
                    .adx(safeDouble(adxIndicator.getValue(idx)))
                    .diPlus(safeDouble(plusDI.getValue(idx)))
                    .diMinus(safeDouble(minusDI.getValue(idx)))
                    .obv(safeDouble(obv.getValue(idx)))
                    .mfi(0.0)   // MFI requires HighPrice/LowPrice/Close/Volume — compute separately if needed
                    .volume(vol)
                    .relativeVolume(vAvg > 0 ? vol / vAvg : 1.0)
                    .ema20(e20)
                    .ema50(e50)
                    .ema200(e200)
                    .bollingerWalk(zp.isHigh() ? price >= upper : price <= lower)
                    .bollingerSqueeze(squeeze)
                    .emaStackOrder(emaStack)
                    .stochRsiK(stochRsiK[idx])
                    .stochRsiD(stochRsiD[idx])
                    .build());
        }

        return enriched;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double safeDouble(org.ta4j.core.num.Num num) {
        try {
            return num == null || num.isNaN() ? 0.0 : num.doubleValue();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private boolean isSqueeze(double[] bandWidths, int idx, int lookback) {
        if (idx < lookback) return false;
        double current = bandWidths[idx];
        for (int i = idx - lookback; i < idx; i++) {
            if (bandWidths[i] <= current) return false;
        }
        return true;  // current is lowest in lookback
    }

    private String emaStackOrder(double price, double e20, double e50, double e200) {
        if (price > e20 && e20 > e50 && e50 > e200) return "BULLISH";
        if (price < e20 && e20 < e50 && e50 < e200) return "BEARISH";
        return "MIXED";
    }

    private double peakAmplitude(double[] amplitudes, int idx, int lookback) {
        double peak = 0;
        for (int i = Math.max(0, idx - lookback); i <= idx; i++) {
            if (amplitudes[i] > peak) peak = amplitudes[i];
        }
        return peak;
    }
}
