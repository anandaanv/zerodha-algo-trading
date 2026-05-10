package com.dtech.ta.patterns.classic;

import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;

/**
 * Test data builders for classic pattern analysis.
 */
public class ClassicPatternTestData {

    /**
     * Build a BarSeries from explicit OHLCV arrays.
     *
     * @param highs high prices
     * @param lows low prices
     * @param closes close prices
     * @param volumes volume values
     * @return a ta4j BarSeries
     */
    public static BarSeries buildSeries(double[] highs, double[] lows, double[] closes, double[] volumes) {
        if (highs.length != lows.length || highs.length != closes.length || highs.length != volumes.length) {
            throw new IllegalArgumentException("all arrays must have the same length");
        }

        BarSeries series = new BaseBarSeriesBuilder().build();
        Instant baseTime = Instant.EPOCH;

        for (int i = 0; i < highs.length; i++) {
            Instant barTime = baseTime.plus(Duration.ofHours(i + 1));
            // Use close as open by default
            series.addBar(BarsLoader.getBar(closes[i], highs[i], lows[i], closes[i], volumes[i], barTime));
        }

        return series;
    }

    /**
     * Build a BarSeries from close prices with synthetic OHLC.
     * Highs = close * 1.01, Lows = close * 0.99, Opens = close, Volumes = 1000.
     *
     * @param closes close prices
     * @return a ta4j BarSeries
     */
    public static BarSeries buildSeries(double[] closes) {
        double[] highs = new double[closes.length];
        double[] lows = new double[closes.length];
        double[] volumes = new double[closes.length];

        for (int i = 0; i < closes.length; i++) {
            highs[i] = closes[i] * 1.01;
            lows[i] = closes[i] * 0.99;
            volumes[i] = 1000.0;
        }

        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build an ascending triangle series with enough bars for pivot extraction.
     * Creates patterns with clear high/low alternation.
     */
    public static BarSeries buildAscendingTriangleSeries() {
        double[] highs = {100, 98, 100, 97, 99, 96, 98, 95, 97, 94, 96, 93, 95, 92, 94};
        double[] lows = {90, 88, 90, 87, 89, 86, 88, 85, 87, 84, 86, 83, 85, 82, 84};
        double[] closes = {95, 93, 95, 92, 94, 91, 93, 90, 92, 89, 91, 88, 90, 87, 89};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a descending triangle series with enough bars for pivot extraction.
     */
    public static BarSeries buildDescendingTriangleSeries() {
        double[] highs = {100, 99, 98, 97, 96, 95, 94, 93, 92, 91, 90, 89, 88, 87, 86};
        double[] lows = {90, 89, 88, 87, 86, 85, 84, 83, 82, 81, 80, 79, 78, 77, 76};
        double[] closes = {95, 94, 93, 92, 91, 90, 89, 88, 87, 86, 85, 84, 83, 82, 81};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a symmetric triangle series with enough bars for pivot extraction.
     */
    public static BarSeries buildSymmetricTriangleSeries() {
        double[] highs = {100, 95, 98, 94, 96, 93, 94, 92, 92, 91, 90, 90, 89};
        double[] lows = {85, 80, 83, 79, 81, 78, 79, 77, 77, 76, 75, 75, 74};
        double[] closes = {92, 87, 90, 86, 88, 85, 86, 84, 84, 83, 82, 82, 81};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Head and Shoulders series with sufficient bars.
     */
    public static BarSeries buildHnsSeries() {
        double[] highs = {95, 90, 105, 88, 93, 95, 92, 91, 90, 89, 88, 87, 86, 85, 84};
        double[] lows = {85, 75, 95, 73, 83, 85, 82, 81, 80, 79, 78, 77, 76, 75, 74};
        double[] closes = {90, 80, 100, 78, 88, 90, 87, 86, 85, 84, 83, 82, 81, 80, 79};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Reverse Head and Shoulders series with sufficient bars.
     */
    public static BarSeries buildReverseHnsSeries() {
        double[] highs = {90, 100, 85, 102, 92, 95, 98, 100, 101, 102, 103, 104, 105, 106, 107};
        double[] lows = {80, 90, 75, 92, 82, 85, 88, 90, 91, 92, 93, 94, 95, 96, 97};
        double[] closes = {85, 95, 78, 100, 88, 92, 95, 97, 99, 101, 103, 105, 107, 109, 111};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Double Top series with sufficient bars.
     */
    public static BarSeries buildDoubleTopSeries() {
        double[] highs = {100, 98, 100, 97, 99, 96, 98, 95, 97, 94, 96, 93, 95, 92, 94};
        double[] lows = {90, 88, 90, 87, 89, 86, 88, 85, 87, 84, 86, 83, 85, 82, 84};
        double[] closes = {95, 93, 95, 92, 94, 91, 93, 90, 92, 89, 91, 88, 90, 87, 89};
        double[] volumes = {5000, 4500, 4000, 3500, 3000, 2500, 2000, 1500, 1500, 1400, 1300, 1200, 1100, 1000, 900};
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Double Bottom series with sufficient bars.
     */
    public static BarSeries buildDoubleBottomSeries() {
        double[] highs = {100, 98, 100, 97, 99, 96, 98, 95, 97, 94, 96, 93, 95, 92, 94};
        double[] lows = {80, 78, 80, 77, 79, 76, 78, 75, 77, 74, 76, 73, 75, 72, 74};
        double[] closes = {85, 83, 85, 82, 84, 81, 83, 80, 82, 79, 81, 78, 80, 77, 79};
        double[] volumes = {5000, 4500, 4000, 3500, 3000, 2500, 2000, 1500, 1500, 1400, 1300, 1200, 1100, 1000, 900};
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bullish VCP series with enough bars for pivot extraction.
     * Expected pivots when extracted with barsLeft=2, barsRight=2:
     * A (HIGH) at index 3: 100
     * B (LOW) at index 6: 85
     * C (HIGH) at index 9: 99
     * D (LOW) at index 12: 87
     */
    public static BarSeries buildBullishVcpSeries() {
        double[] highs = {98, 96, 98, 100, 98, 96, 95, 96, 97, 99, 97, 96, 97, 98, 97, 96, 95};
        double[] lows = {90, 88, 90, 92, 90, 88, 85, 88, 89, 91, 89, 88, 87, 90, 89, 88, 87};
        double[] closes = {95, 92, 95, 97, 95, 92, 90, 92, 94, 96, 94, 92, 91, 94, 93, 92, 91};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bearish VCP series with enough bars for pivot extraction.
     * Expected pivots when extracted with barsLeft=2, barsRight=2:
     * A (LOW) at index 3: 85
     * B (HIGH) at index 6: 100
     * C (LOW) at index 9: 86
     * D (HIGH) at index 12: 98
     */
    public static BarSeries buildBearishVcpSeries() {
        double[] highs = {97, 99, 97, 96, 98, 100, 101, 100, 99, 98, 100, 99, 98, 97, 98, 99, 100};
        double[] lows = {89, 91, 89, 85, 90, 92, 93, 92, 91, 86, 92, 91, 90, 89, 90, 91, 92};
        double[] closes = {94, 96, 94, 91, 96, 98, 99, 98, 97, 92, 98, 97, 96, 95, 96, 97, 98};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bullish VCP series that later gets breached (closes above C).
     * VCP forms through index ~12, then bars 13-16 breach C resistance.
     */
    public static BarSeries buildBullishVcpBreachedSeries() {
        double[] highs = {98, 96, 98, 100, 98, 96, 95, 96, 97, 99, 97, 96, 97, 102, 104, 106, 108};
        double[] lows = {90, 88, 90, 92, 90, 88, 85, 88, 89, 91, 89, 88, 87, 100, 102, 104, 106};
        double[] closes = {95, 92, 95, 97, 95, 92, 90, 92, 94, 96, 94, 92, 91, 101, 103, 105, 107};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bearish VCP series that later gets breached (closes below C).
     * VCP forms through index ~12, then bars 13-16 breach C support.
     */
    public static BarSeries buildBearishVcpBreachedSeries() {
        double[] highs = {97, 99, 97, 96, 98, 100, 101, 100, 99, 98, 100, 99, 98, 85, 83, 81, 79};
        double[] lows = {89, 91, 89, 85, 90, 92, 93, 92, 91, 86, 92, 91, 90, 75, 73, 71, 69};
        double[] closes = {94, 96, 94, 91, 96, 98, 99, 98, 97, 92, 98, 97, 96, 80, 78, 76, 74};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bullish Flag series.
     * Long base, then sharp rally to a new 7-day high (>30-day and 90-day), SMA20 > SMA50 * 1.08, then 5-8 bar consolidation.
     * Total 60 bars to ensure sufficient data for SMA20, SMA50, and window checks.
     */
    public static BarSeries buildBullishFlagSeries() {
        // Base: 20 bars at steady level around 100
        double[] closePart1 = {100, 100.5, 100.2, 100.3, 100.1, 100.4, 100.2, 100.3, 100.1, 100.5,
                               100.2, 100.3, 100.4, 100.1, 100.2, 100.3, 100.5, 100.2, 100.3, 100.1};

        // Rally: 15 bars, rising from 100 to 115 (sharp, new 7-day high at bar 34)
        double[] closePart2 = {100.5, 102, 104, 106, 108, 110, 112, 113, 114, 115,
                               115.2, 115.5, 115.3, 115.1, 114.5};

        // Flag consolidation: 5-8 bars tight range around 112-114 (above Fib 50% which is ~107.5)
        double[] closePart3 = {113.5, 112.8, 113.2, 112.5, 113.0, 112.7};

        // Total 41 bars + more for lookback
        double[] allCloses = new double[60];
        int idx = 0;
        for (double c : closePart1) {
            allCloses[idx++] = c;
        }
        for (double c : closePart2) {
            allCloses[idx++] = c;
        }
        for (double c : closePart3) {
            allCloses[idx++] = c;
        }
        // Pad remaining bars
        while (idx < 60) {
            allCloses[idx] = 112.5 + (Math.random() * 1.0);
            idx++;
        }

        double[] highs = new double[60];
        double[] lows = new double[60];
        double[] volumes = new double[60];

        for (int i = 0; i < 60; i++) {
            highs[i] = allCloses[i] * 1.005;
            lows[i] = allCloses[i] * 0.995;
            volumes[i] = 2000.0;
        }

        return buildSeries(highs, lows, allCloses, volumes);
    }

    /**
     * Build a Bearish Flag series (mirror of bullish).
     * Long base, then sharp drop to a new 7-day low (<30-day and 90-day), SMA20 < SMA50 / 1.08, then 5-8 bar consolidation.
     */
    public static BarSeries buildBearishFlagSeries() {
        // Base: 20 bars at steady level around 100
        double[] closePart1 = {100, 99.5, 99.8, 99.7, 99.9, 99.6, 99.8, 99.7, 99.9, 99.5,
                               99.8, 99.7, 99.6, 99.9, 99.8, 99.7, 99.5, 99.8, 99.7, 99.9};

        // Drop: 15 bars, falling from 100 to 85 (sharp, new 7-day low at bar 34)
        double[] closePart2 = {99.5, 98, 96, 94, 92, 90, 88, 87, 86, 85,
                               84.8, 84.5, 84.7, 84.9, 85.5};

        // Flag consolidation: 5-8 bars tight range around 87-89 (below Fib 50% which is ~92.5)
        double[] closePart3 = {86.5, 87.2, 86.8, 87.5, 87.0, 87.3};

        double[] allCloses = new double[60];
        int idx = 0;
        for (double c : closePart1) {
            allCloses[idx++] = c;
        }
        for (double c : closePart2) {
            allCloses[idx++] = c;
        }
        for (double c : closePart3) {
            allCloses[idx++] = c;
        }
        while (idx < 60) {
            allCloses[idx] = 87.5 + (Math.random() * 1.0);
            idx++;
        }

        double[] highs = new double[60];
        double[] lows = new double[60];
        double[] volumes = new double[60];

        for (int i = 0; i < 60; i++) {
            highs[i] = allCloses[i] * 1.005;
            lows[i] = allCloses[i] * 0.995;
            volumes[i] = 2000.0;
        }

        return buildSeries(highs, lows, allCloses, volumes);
    }

    /**
     * Build an Uptrend Line series.
     * 30+ bars with 3+ ascending lows forming a clear rising support line.
     * No breach below the trendline.
     */
    public static BarSeries buildUptrendLineSeries() {
        double[] closes = {100, 101, 102, 101.5, 103, 102.5, 104, 103.5, 105, 104.5,
                           106, 105.5, 107, 106.5, 108, 107.5, 109, 108.5, 110, 109.5,
                           111, 110.5, 112, 111.5, 113, 112.5, 114, 113.5, 115, 114.5,
                           116, 115.5, 117};

        double[] highs = new double[closes.length];
        double[] lows = new double[closes.length];
        double[] volumes = new double[closes.length];

        for (int i = 0; i < closes.length; i++) {
            highs[i] = closes[i] + 0.5;  // tight range
            lows[i] = closes[i] - 0.5;   // ascending lows
            volumes[i] = 2000.0;
        }

        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Downtrend Line series.
     * 30+ bars with 3+ descending highs forming a clear falling resistance line.
     * No breach above the trendline.
     */
    public static BarSeries buildDowntrendLineSeries() {
        double[] closes = {150, 149, 148, 148.5, 147, 147.5, 146, 146.5, 145, 145.5,
                           144, 144.5, 143, 143.5, 142, 142.5, 141, 141.5, 140, 140.5,
                           139, 139.5, 138, 138.5, 137, 137.5, 136, 136.5, 135, 135.5,
                           134, 134.5, 133};

        double[] highs = new double[closes.length];
        double[] lows = new double[closes.length];
        double[] volumes = new double[closes.length];

        for (int i = 0; i < closes.length; i++) {
            highs[i] = closes[i] + 0.5;  // tight range, descending highs
            lows[i] = closes[i] - 0.5;
            volumes[i] = 2000.0;
        }

        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bullish ABCD series with clean harmonic pattern.
     * Expected pivots:
     * A (HIGH) ~ bar 5: 120
     * B (LOW) ~ bar 9: 80
     * C (HIGH) ~ bar 15: 100
     * D (LOW) ~ bar 20: ~90 (current close)
     * cRetrace ~ 0.618, ab_cd_ext = 100 - (120-80) = 60
     */
    public static BarSeries buildBullishAbcdSeries() {
        double[] highs = {100, 102, 105, 110, 115, 120, 118, 115, 110, 85, 90, 95, 98, 101, 105, 100, 98, 95, 92, 88, 90};
        double[] lows = {90, 95, 100, 105, 110, 115, 110, 105, 100, 80, 85, 90, 93, 96, 100, 95, 93, 90, 87, 83, 85};
        double[] closes = {95, 100, 103, 108, 112, 118, 115, 112, 108, 82, 88, 93, 96, 99, 103, 98, 96, 93, 90, 85, 88};
        double[] volumes = new double[closes.length];
        for (int i = 0; i < volumes.length; i++) {
            volumes[i] = 1000.0;
        }
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bearish ABCD series (mirror of bullish).
     * Expected pivots:
     * A (LOW) ~ bar 5: 80
     * B (HIGH) ~ bar 9: 120
     * C (LOW) ~ bar 15: 100
     * D (HIGH) ~ bar 20: ~90 (current close)
     */
    public static BarSeries buildBearishAbcdSeries() {
        double[] highs = {110, 105, 102, 100, 95, 85, 88, 91, 96, 125, 120, 115, 112, 109, 105, 110, 112, 115, 118, 122, 120};
        double[] lows = {100, 95, 90, 85, 80, 75, 80, 85, 90, 115, 110, 105, 102, 99, 95, 100, 102, 105, 108, 112, 110};
        double[] closes = {105, 100, 95, 90, 85, 78, 83, 88, 93, 120, 115, 110, 107, 104, 100, 105, 107, 110, 113, 117, 115};
        double[] volumes = new double[closes.length];
        for (int i = 0; i < volumes.length; i++) {
            volumes[i] = 1000.0;
        }
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bullish BAT series with clean harmonic pattern.
     * Expected pivots:
     * X (LOW) ~ bar 2: 70
     * A (HIGH) ~ bar 6: 130
     * B (LOW) ~ bar 10: 100
     * C (HIGH) ~ bar 15: 120
     * D (LOW) ~ bar 20: ~105 (current close)
     * bRetrace ~ 0.5, cRetrace ~ 0.618
     */
    public static BarSeries buildBullishBatSeries() {
        double[] highs = {100, 95, 75, 85, 100, 120, 130, 125, 120, 110, 100, 105, 110, 115, 120, 118, 115, 112, 108, 105, 108};
        double[] lows = {90, 85, 70, 80, 95, 115, 125, 120, 115, 105, 95, 100, 105, 110, 115, 113, 110, 107, 103, 100, 103};
        double[] closes = {95, 90, 72, 82, 98, 118, 128, 122, 118, 108, 98, 103, 108, 113, 118, 116, 113, 110, 106, 103, 106};
        double[] volumes = new double[closes.length];
        for (int i = 0; i < volumes.length; i++) {
            volumes[i] = 1000.0;
        }
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bearish BAT series (mirror of bullish).
     * Expected pivots:
     * X (HIGH) ~ bar 2: 130
     * A (LOW) ~ bar 6: 70
     * B (HIGH) ~ bar 10: 100
     * C (LOW) ~ bar 15: 80
     * D (HIGH) ~ bar 20: ~95 (current close)
     */
    public static BarSeries buildBearishBatSeries() {
        double[] highs = {100, 115, 140, 130, 115, 95, 70, 75, 80, 90, 100, 95, 90, 85, 80, 82, 85, 88, 92, 95, 92};
        double[] lows = {90, 105, 130, 120, 105, 85, 60, 65, 70, 80, 90, 85, 80, 75, 70, 72, 75, 78, 82, 85, 82};
        double[] closes = {95, 110, 135, 125, 110, 90, 65, 70, 75, 85, 95, 90, 85, 80, 75, 77, 80, 83, 87, 90, 87};
        double[] volumes = new double[closes.length];
        for (int i = 0; i < volumes.length; i++) {
            volumes[i] = 1000.0;
        }
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bullish Gartley series.
     * Gartley: b_retrace = 0.618, c_retrace = 0.618, terminal = 0.786 XA
     * Expected pivots:
     * X (LOW) ~ bar 2: 80
     * A (HIGH) ~ bar 6: 150
     * B (LOW) ~ bar 10: 118 (0.618 retrace of 70)
     * C (HIGH) ~ bar 15: 130 (0.618 retrace of 32)
     * D (LOW) ~ bar 20: 110 (current close, retraced toward terminal ~117.8)
     */
    public static BarSeries buildBullishGartleySeries() {
        double[] highs = {100, 85, 75, 80, 95, 145, 150, 145, 140, 130, 120, 125, 130, 135, 140, 138, 135, 132, 128, 125, 128};
        double[] lows = {90, 80, 70, 75, 90, 140, 145, 140, 135, 125, 115, 120, 125, 130, 135, 133, 130, 127, 123, 120, 123};
        double[] closes = {95, 82, 72, 78, 92, 148, 148, 142, 138, 128, 118, 123, 128, 133, 138, 136, 133, 130, 126, 123, 126};
        double[] volumes = new double[closes.length];
        for (int i = 0; i < volumes.length; i++) {
            volumes[i] = 1000.0;
        }
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bearish Gartley series (mirror of bullish).
     * X (HIGH) ~ bar 2: 150
     * A (LOW) ~ bar 6: 80
     * B (HIGH) ~ bar 10: 118
     * C (LOW) ~ bar 15: 100
     * D (HIGH) ~ bar 20: 110 (current close)
     */
    public static BarSeries buildBearishGartleySeries() {
        double[] highs = {100, 145, 160, 150, 135, 95, 80, 85, 90, 100, 110, 105, 100, 95, 90, 92, 95, 98, 102, 105, 102};
        double[] lows = {90, 135, 150, 140, 125, 85, 70, 75, 80, 90, 100, 95, 90, 85, 80, 82, 85, 88, 92, 95, 92};
        double[] closes = {95, 140, 155, 145, 130, 90, 75, 80, 85, 95, 105, 100, 95, 90, 85, 87, 90, 93, 97, 100, 97};
        double[] volumes = new double[closes.length];
        for (int i = 0; i < volumes.length; i++) {
            volumes[i] = 1000.0;
        }
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bullish Crab series.
     * Crab: b_retrace = 0.618, c_retrace = 0.618, terminal = 1.618 XA
     * Expected pivots:
     * X (LOW) ~ bar 2: 80
     * A (HIGH) ~ bar 6: 150
     * B (LOW) ~ bar 10: 115 (0.618 retrace of 70)
     * C (HIGH) ~ bar 15: 130 (0.618 retrace of 35)
     * D (LOW) ~ bar 20: 55 (current close, below terminal 46.4)
     */
    public static BarSeries buildBullishCrabSeries() {
        double[] highs = {100, 85, 75, 80, 95, 145, 150, 145, 140, 130, 120, 125, 130, 135, 140, 138, 135, 132, 128, 125, 60};
        double[] lows = {90, 80, 70, 75, 90, 140, 145, 140, 135, 125, 110, 120, 125, 130, 135, 133, 130, 127, 123, 120, 50};
        double[] closes = {95, 82, 72, 78, 92, 148, 148, 142, 138, 128, 115, 123, 128, 133, 138, 136, 133, 130, 126, 123, 55};
        double[] volumes = new double[closes.length];
        for (int i = 0; i < volumes.length; i++) {
            volumes[i] = 1000.0;
        }
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bearish Crab series (mirror of bullish).
     * X (HIGH) ~ bar 2: 150
     * A (LOW) ~ bar 6: 80
     * B (HIGH) ~ bar 10: 115
     * C (LOW) ~ bar 15: 100
     * D (HIGH) ~ bar 20: 140 (current close, above terminal ~153.6)
     */
    public static BarSeries buildBearishCrabSeries() {
        double[] highs = {100, 145, 160, 150, 135, 95, 80, 85, 90, 100, 110, 105, 100, 95, 90, 92, 95, 98, 102, 105, 150};
        double[] lows = {90, 135, 150, 140, 125, 85, 70, 75, 80, 90, 100, 95, 90, 85, 80, 82, 85, 88, 92, 95, 140};
        double[] closes = {95, 140, 155, 145, 130, 90, 75, 80, 85, 95, 105, 100, 95, 90, 85, 87, 90, 93, 97, 100, 145};
        double[] volumes = new double[closes.length];
        for (int i = 0; i < volumes.length; i++) {
            volumes[i] = 1000.0;
        }
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bullish Butterfly series.
     * Butterfly: b_retrace = 0.786, c_retrace = 0.786, terminal = 1.27 XA
     * Expected pivots:
     * X (LOW) ~ bar 2: 80
     * A (HIGH) ~ bar 6: 150
     * B (LOW) ~ bar 10: 118.6 (0.786 retrace of 70)
     * C (HIGH) ~ bar 15: 130 (0.786 retrace of 32)
     * D (LOW) ~ bar 20: 65 (current close, below terminal ~87.1)
     */
    public static BarSeries buildBullishButterflySeries() {
        double[] highs = {100, 85, 75, 80, 95, 145, 150, 145, 140, 130, 120, 125, 130, 135, 140, 138, 135, 132, 128, 125, 70};
        double[] lows = {90, 80, 70, 75, 90, 140, 145, 140, 135, 125, 115, 120, 125, 130, 135, 133, 130, 127, 123, 120, 60};
        double[] closes = {95, 82, 72, 78, 92, 148, 148, 142, 138, 128, 118, 123, 128, 133, 138, 136, 133, 130, 126, 123, 65};
        double[] volumes = new double[closes.length];
        for (int i = 0; i < volumes.length; i++) {
            volumes[i] = 1000.0;
        }
        return buildSeries(highs, lows, closes, volumes);
    }

    /**
     * Build a Bearish Butterfly series (mirror of bullish).
     * X (HIGH) ~ bar 2: 150
     * A (LOW) ~ bar 6: 80
     * B (HIGH) ~ bar 10: 118.6
     * C (LOW) ~ bar 15: 100
     * D (HIGH) ~ bar 20: 130 (current close, above terminal ~112.9)
     */
    public static BarSeries buildBearishButterflySeries() {
        double[] highs = {100, 145, 160, 150, 135, 95, 80, 85, 90, 100, 110, 105, 100, 95, 90, 92, 95, 98, 102, 105, 140};
        double[] lows = {90, 135, 150, 140, 125, 85, 70, 75, 80, 90, 100, 95, 90, 85, 80, 82, 85, 88, 92, 95, 130};
        double[] closes = {95, 140, 155, 145, 130, 90, 75, 80, 85, 95, 105, 100, 95, 90, 85, 87, 90, 93, 97, 100, 135};
        double[] volumes = new double[closes.length];
        for (int i = 0; i < volumes.length; i++) {
            volumes[i] = 1000.0;
        }
        return buildSeries(highs, lows, closes, volumes);
    }
}
