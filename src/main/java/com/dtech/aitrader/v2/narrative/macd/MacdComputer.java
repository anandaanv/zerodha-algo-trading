package com.dtech.aitrader.v2.narrative.macd;

import com.dtech.chartdata.model.OhlcBarDTO;

import java.time.Instant;
import java.util.List;

/**
 * Computes MACD (Moving Average Convergence Divergence) for a series of OHLC bars.
 *
 * Uses first-close EMA seeding (NOT Wilder, NOT SMA-seeded) to match standard MACD references:
 * - ema[0] = close[0]
 * - ema[i] = close[i] * k + ema[i-1] * (1-k), where k = 2.0 / (period + 1)
 */
public class MacdComputer {

    private MacdComputer() {
        // Static utility class
    }

    /**
     * Compute MACD for a series of OHLC bars.
     *
     * @param bars Input OHLC bars (must have at least slowPeriod bars for meaningful results)
     * @param fastPeriod EMA period for fast line (typically 12)
     * @param slowPeriod EMA period for slow line (typically 26)
     * @param signalPeriod EMA period for signal line (typically 9)
     * @param symbol Trading symbol (e.g., "RELIANCE")
     * @param timeframe Timeframe string (e.g., "Week")
     * @return MacdSeries containing macdLine, signalLine, histogram, and metadata
     */
    public static MacdSeries compute(List<OhlcBarDTO> bars, int fastPeriod, int slowPeriod,
                                     int signalPeriod, String symbol, String timeframe) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be null or empty");
        }

        int n = bars.size();

        // Extract close prices
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = bars.get(i).getClose();
        }

        // Compute EMAs with first-close seeding
        double[] fastEma = computeEma(closes, fastPeriod);
        double[] slowEma = computeEma(closes, slowPeriod);

        // Compute MACD line
        double[] macdLine = new double[n];
        for (int i = 0; i < n; i++) {
            macdLine[i] = fastEma[i] - slowEma[i];
        }

        // Compute signal line (EMA of MACD with first-value seeding)
        double[] signalLine = computeEma(macdLine, signalPeriod);

        // Compute histogram
        double[] histogram = new double[n];
        for (int i = 0; i < n; i++) {
            histogram[i] = macdLine[i] - signalLine[i];
        }

        // Build bar indices and timestamps
        int[] barIndices = new int[n];
        Instant[] barTimestamps = new Instant[n];
        for (int i = 0; i < n; i++) {
            barIndices[i] = i;
            barTimestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
        }

        return MacdSeries.builder()
                .macdLine(macdLine)
                .signalLine(signalLine)
                .histogram(histogram)
                .barIndices(barIndices)
                .barTimestamps(barTimestamps)
                .symbol(symbol)
                .timeframe(timeframe)
                .build();
    }

    /**
     * Compute EMA with first-value seeding.
     *
     * Convention:
     * - ema[0] = series[0]
     * - ema[i] = series[i] * k + ema[i-1] * (1-k), where k = 2.0 / (period + 1)
     *
     * @param series Input series
     * @param period EMA period
     * @return EMA array of same length as input series
     */
    private static double[] computeEma(double[] series, int period) {
        int n = series.length;
        double[] ema = new double[n];

        if (n == 0) {
            return ema;
        }

        double k = 2.0 / (period + 1);
        ema[0] = series[0];

        for (int i = 1; i < n; i++) {
            ema[i] = series[i] * k + ema[i - 1] * (1 - k);
        }

        return ema;
    }
}
