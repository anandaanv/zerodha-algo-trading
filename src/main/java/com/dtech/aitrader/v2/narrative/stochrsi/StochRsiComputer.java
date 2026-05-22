package com.dtech.aitrader.v2.narrative.stochrsi;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.StochasticRSIIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Instant;
import java.util.List;

/**
 * StochRSI(14,14,3,3) via ta4j {@link StochasticRSIIndicator}.
 *
 * <p>ta4j's {@code StochasticRSIIndicator(series, barCount)} returns raw StochRSI in [0, 1]: the
 * stochastic of RSI with the same lookback. Chande-Kroll's canonical version applies two SMA-3
 * smoothings on top: Slow %K = SMA(raw, 3); Slow %D = SMA(Slow %K, 3). We multiply the output
 * by 100 so the scale matches RSI/Stoch (zones live at 80/20/50 just like Stoch).
 *
 * <p>FAILURE_004 (the dominant StochRSI caveat per delta 2fde845f): this indicator fires 2-4×
 * more often than RSI. Aggressive smoothing here doesn't remove the fire-rate; that's the job of
 * the narrative noise filter (significance thresholds + persistence). Our role here is just to
 * deliver Slow %K / Slow %D series cleanly.
 *
 * <p>Warmup clamp same as Stoch/RSI — the (RSI → stochOfRsi → SMA-3 → SMA-3) chain emits
 * inflated early values that would poison the adaptive-significance pivot ATR.
 */
public final class StochRsiComputer {

    private StochRsiComputer() {}

    public static StochRsiSeries compute(List<OhlcBarDTO> bars, int period, int kSmoothing, int dSmoothing,
                                          String symbol, String timeframe) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be null or empty");
        }
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();
        for (OhlcBarDTO b : bars) {
            Instant instant = Instant.ofEpochSecond(b.getTime());
            Bar taBar = BarsLoader.getBar(b.getOpen(), b.getHigh(), b.getLow(), b.getClose(),
                    b.getVolume(), instant);
            series.addBar(taBar);
        }

        // ta4j 0.22.7's StochasticRSIIndicator returned NaN for every bar in this build (both
        // BarSeries and RSIIndicator constructors). Workaround: use ta4j for RSI (battle-tested),
        // then compute stoch-of-RSI ourselves — that's just min/max over a rolling window.
        // Chande-Kroll formula: stochRSI[i] = (RSI[i] - min(RSI, period)) / (max(RSI, period) - min(RSI, period)).
        RSIIndicator rsi = new RSIIndicator(new ClosePriceIndicator(series), period);
        int n = bars.size();
        double[] rsiArr = new double[n];
        for (int i = 0; i < n; i++) {
            rsiArr[i] = rsi.getValue(i).doubleValue();
        }
        // Raw StochRSI [0,1]
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < period - 1) { raw[i] = 0.0; continue; }
            double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
            for (int j = i - period + 1; j <= i; j++) {
                if (rsiArr[j] < mn) mn = rsiArr[j];
                if (rsiArr[j] > mx) mx = rsiArr[j];
            }
            raw[i] = mx > mn ? (rsiArr[i] - mn) / (mx - mn) : 0.5;
        }
        // Slow %K = SMA(raw, kSmoothing); Slow %D = SMA(Slow %K, dSmoothing). Scale to 0-100.
        double[] kArr = new double[n];
        double[] dArr = new double[n];
        for (int i = 0; i < n; i++) {
            int kStart = Math.max(0, i - kSmoothing + 1);
            double ksum = 0;
            for (int j = kStart; j <= i; j++) ksum += raw[j];
            kArr[i] = (ksum / (i - kStart + 1)) * 100.0;
        }
        for (int i = 0; i < n; i++) {
            int dStart = Math.max(0, i - dSmoothing + 1);
            double dsum = 0;
            for (int j = dStart; j <= i; j++) dsum += kArr[j];
            dArr[i] = dsum / (i - dStart + 1);
        }
        Instant[] timestamps = new Instant[n];
        for (int i = 0; i < n; i++) {
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
        }

        // Warmup: ta4j returns NaN until rsi+stochOfRsi+SMA-3+SMA-3 has data. Find the first
        // non-NaN index dynamically (calculated warmup formulas tend to be brittle across ta4j
        // versions) and clamp earlier bars to that stable value. Without this the pivot engine
        // sees NaN at the head of the series and emits no pivots / NaN posture.
        int firstValid = -1;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(kArr[i]) && !Double.isNaN(dArr[i])) {
                firstValid = i;
                break;
            }
        }
        if (firstValid > 0) {
            double kStable = kArr[firstValid];
            double dStable = dArr[firstValid];
            for (int i = 0; i < firstValid; i++) {
                kArr[i] = kStable;
                dArr[i] = dStable;
            }
        }
        return StochRsiSeries.builder()
                .stochrsiK(kArr)
                .stochrsiD(dArr)
                .barTimestamps(timestamps)
                .symbol(symbol)
                .timeframe(timeframe)
                .build();
    }
}
