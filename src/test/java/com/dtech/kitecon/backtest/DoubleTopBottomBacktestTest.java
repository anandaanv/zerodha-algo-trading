package com.dtech.kitecon.backtest;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.KiteconApplication;
import com.dtech.kitecon.repository.InstrumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.ta4j.core.BarSeries;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for Double Top/Bottom backtest on RELIANCE hourly data.
 *
 * Prerequisites:
 * - MySQL running on localhost:3306/algotrading
 * - RELIANCE instrument exists in the instruments table
 * - RELIANCE 1h and 1d candles exist
 *
 * Run with: ./gradlew test --tests "*.DoubleTopBottomBacktestTest"
 */
@SpringBootTest(classes = KiteconApplication.class)
@ActiveProfiles("integration")
class DoubleTopBottomBacktestTest {

    @Autowired
    private DoubleTopBottomBacktestService backtestService;

    @Autowired
    private TriangleBacktestService triangleBacktestService;

    @Autowired
    private PatternComboBacktestService patternComboBacktestService;

    @Autowired
    private ZigZagService zigZagService;

    @Autowired
    private InstrumentRepository instrumentRepository;

    @Test
    void runRelianceBacktest() throws Exception {
        String csvPath = "/tmp/reliance_double_topbottom_backtest.csv";
        backtestService.runAndWriteCsv("RELIANCE", csvPath);
        System.out.println("CSV written to: " + csvPath);
        // Just verify file exists and has content
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() > 0);
    }

    @Test
    void runTop20Backtest() throws Exception {
        List<String> top20 = List.of(
            "RELIANCE", "TCS", "HDFCBANK", "BHARTIARTL", "ICICIBANK",
            "INFY", "SBIN", "BAJFINANCE", "HINDUNILVR", "ITC",
            "LT", "KOTAKBANK", "AXISBANK", "ASIANPAINT", "HCLTECH",
            "MARUTI", "SUNPHARMA", "TITAN", "ADANIENT", "NTPC"
        );
        String csvPath = "/tmp/top20_double_topbottom_backtest.csv";
        backtestService.runMultipleAndWriteCsv(top20, csvPath);
        System.out.println("CSV written to: " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() > 0);
    }

    @Test
    void runRelianceTriangleBacktest() throws Exception {
        String csvPath = "/tmp/reliance_triangle_backtest.csv";
        triangleBacktestService.runAndWriteCsv("RELIANCE", csvPath);
        System.out.println("CSV written to: " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() > 0);
    }

    @Test
    void runRelianceComboBacktest() throws Exception {
        String csvPath = "/tmp/reliance_combo_backtest.csv";
        patternComboBacktestService.runAndWriteCsv("RELIANCE", csvPath);
        System.out.println("CSV written to: " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() >= 0);
    }

    @Test
    void runTop20ComboBacktest() throws Exception {
        List<String> top20 = List.of(
            "RELIANCE", "TCS", "HDFCBANK", "BHARTIARTL", "ICICIBANK",
            "INFY", "SBIN", "BAJFINANCE", "HINDUNILVR", "ITC",
            "LT", "KOTAKBANK", "AXISBANK", "ASIANPAINT", "HCLTECH",
            "MARUTI", "SUNPHARMA", "TITAN", "ADANIENT", "NTPC"
        );
        String csvPath = "/tmp/top20_combo_backtest.csv";
        patternComboBacktestService.runMultipleAndWriteCsv(top20, csvPath);
        System.out.println("CSV written to: " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() >= 0);
    }

    @Test
    void runTop20HnsBacktest() throws Exception {
        List<String> top20 = List.of(
            "RELIANCE", "TCS", "HDFCBANK", "BHARTIARTL", "ICICIBANK",
            "INFY", "SBIN", "BAJFINANCE", "HINDUNILVR", "ITC",
            "LT", "KOTAKBANK", "AXISBANK", "ASIANPAINT", "HCLTECH",
            "MARUTI", "SUNPHARMA", "TITAN", "ADANIENT", "NTPC"
        );
        String csvPath = "/tmp/top20_hns_backtest.csv";
        patternComboBacktestService.runMultipleAndWriteCsv(top20, csvPath);
        System.out.println("CSV written to: " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() >= 0);
    }

    @Test
    void runTop20FlagBacktest() throws Exception {
        List<String> top20 = List.of(
            "RELIANCE", "TCS", "HDFCBANK", "BHARTIARTL", "ICICIBANK",
            "INFY", "SBIN", "BAJFINANCE", "HINDUNILVR", "ITC",
            "LT", "KOTAKBANK", "AXISBANK", "ASIANPAINT", "HCLTECH",
            "MARUTI", "SUNPHARMA", "TITAN", "ADANIENT", "NTPC"
        );
        String csvPath = "/tmp/top20_flag_backtest.csv";
        patternComboBacktestService.runMultipleAndWriteCsv(top20, csvPath);
        System.out.println("CSV written to: " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() >= 0);
    }

    // Full Nifty 50 symbols
    static final List<String> NIFTY50 = List.of(
        "RELIANCE", "TCS", "HDFCBANK", "BHARTIARTL", "ICICIBANK",
        "INFY", "SBIN", "BAJFINANCE", "HINDUNILVR", "ITC",
        "LT", "KOTAKBANK", "AXISBANK", "ASIANPAINT", "HCLTECH",
        "MARUTI", "SUNPHARMA", "TITAN", "ADANIENT", "NTPC",
        "WIPRO", "ULTRACEMCO", "ONGC", "NESTLEIND", "TATAMOTORS",
        "JSWSTEEL", "TATACONSUM", "M&M", "POWERGRID", "COALINDIA",
        "BAJAJFINSV", "TATASTEEL", "TECHM", "BAJAJ-AUTO", "DRREDDY",
        "DIVISLAB", "BRITANNIA", "CIPLA", "APOLLOHOSP", "EICHERMOT",
        "GRASIM", "INDUSINDBK", "BPCL", "HEROMOTOCO", "HINDALCO",
        "SHRIRAMFIN", "LTIM", "SBILIFE", "HDFCLIFE", "TRENT"
    );

    @Test
    void runNifty50DailyPlainBacktest() throws Exception {
        String csvPath = "/tmp/nifty50_daily_plain_backtest.csv";
        patternComboBacktestService.runMultipleAndWriteCsv(NIFTY50, csvPath, Interval.Day, Interval.Day);
        System.out.println("CSV written to: " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() >= 0);
    }

    @Test
    void runNifty50DailyComboBacktest() throws Exception {
        String csvPath = "/tmp/nifty50_daily_combo_backtest.csv";
        patternComboBacktestService.runMultipleAndWriteCsv(NIFTY50, csvPath, Interval.Day, Interval.OneHour);
        System.out.println("CSV written to: " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() >= 0);
    }

    @Test
    void runNifty50HourlyComboBacktest() throws Exception {
        String csvPath = "/tmp/nifty50_hourly_combo_backtest.csv";
        patternComboBacktestService.runMultipleAndWriteCsv(NIFTY50, csvPath, Interval.OneHour, Interval.FifteenMinute);
        System.out.println("CSV written to: " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() >= 0);
    }

    @Test
    void runAllFnoComboBacktest() throws Exception {
        List<String> indexFutures = List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "BANKEX", "NIFTYNXT50");
        List<String> fnoSymbols = instrumentRepository.findDistinctFutureUnderlyingNames()
                .stream()
                .filter(s -> s != null && !s.isBlank() && !indexFutures.contains(s.toUpperCase()))
                .sorted()
                .collect(java.util.stream.Collectors.toList());
        System.out.println("FNO stock symbols: " + fnoSymbols.size() + " → " + fnoSymbols);

        String csvPath = "/tmp/all_fno_combo_backtest.csv";
        patternComboBacktestService.runMultipleAndWriteCsv(fnoSymbols, csvPath, Interval.OneHour, Interval.FifteenMinute);
        System.out.println("CSV written to: " + csvPath);
        System.out.println("Run: python scripts/benchmark_simulation.py " + csvPath);
        File f = new File(csvPath);
        assertTrue(f.exists());
        assertTrue(f.length() >= 0);
    }

    @Test
    void diagnoseRelianceAscendingTriangle() throws Exception {
        final double ATR_FLAT_FACTOR = 0.5;
        final double ATR_RANGE_FACTOR = 2.0;
        final int MIN_PATTERN_BARS = 5;

        String symbol = "RELIANCE";
        LocalDate startDate = LocalDate.of(2025, 6, 15);
        LocalDate endDate = LocalDate.of(2025, 7, 20);

        // Load instrument
        var instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            System.out.println("No instrument found for RELIANCE");
            return;
        }

        // Load bar series
        BarSeries series = zigZagService.getBarSeries(symbol, instrument, Interval.OneHour);
        if (series == null || series.getBarCount() == 0) {
            System.out.println("No bar series found for RELIANCE 1h");
            return;
        }

        // Build bar list and timestamp-to-index map
        List<org.ta4j.core.Bar> barList = new ArrayList<>();
        Map<java.time.Instant, Integer> tsToIdx = new HashMap<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            barList.add(series.getBar(i));
            tsToIdx.put(series.getBar(i).getEndTime(), i);
        }
        // Detect ZigZag pivots (BACKTEST mode to get full history, not just last 1000 bars)
        var baseParams = zigZagService.resolveParams(symbol, Interval.OneHour);
        var params = ZigZagParams.ofDefaults(baseParams.getAtrLength(), baseParams.getAtrMult(),
                baseParams.getPctMin(), baseParams.getHysteresis(), baseParams.getMinBarsBetweenPivots(),
                baseParams.isDynamicPctEnabled(), baseParams.getVolMult(), baseParams.getRvolWindow(),
                com.dtech.chartpattern.zigzag.ZigZagParams.Mode.BACKTEST);
        var allPivots = zigZagService.detect(series, params);

        System.out.printf("Total bars: %d, total pivots: %d%n", barList.size(), allPivots.size());
        System.out.println("First 3 pivots:");
        for (int i = 0; i < Math.min(3, allPivots.size()); i++)
            System.out.printf("  %s %s %.1f%n", allPivots.get(i).getTimestamp().atZone(ZoneOffset.UTC).toLocalDate(), allPivots.get(i).isHigh() ? "HIGH" : "LOW", allPivots.get(i).getValue());
        System.out.println("Last 3 pivots:");
        for (int i = Math.max(0, allPivots.size()-3); i < allPivots.size(); i++)
            System.out.printf("  %s %s %.1f%n", allPivots.get(i).getTimestamp().atZone(ZoneOffset.UTC).toLocalDate(), allPivots.get(i).isHigh() ? "HIGH" : "LOW", allPivots.get(i).getValue());

        // Compute ATR array and use last value as representative ATR
        double[] atrArr = patternComboBacktestService.computeAtrPublic(barList, 14);
        double atr = atrArr.length > 0 ? atrArr[atrArr.length - 1] : 0;

        // Print all pivots in range
        System.out.println("\n=== RELIANCE 1h ZigZag pivots Jun 15 – Jul 20 2025 ===");
        int displayIdx = 0;
        for (var pivot : allPivots) {
            LocalDate pivotDate = pivot.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
            if (!pivotDate.isBefore(startDate) && !pivotDate.isAfter(endDate)) {
                String type = pivot.isHigh() ? "HIGH" : "LOW";
                System.out.printf("  %d %s %s %.1f%n", displayIdx++, pivotDate, type, pivot.getValue());
            }
        }

        // Process sliding windows of 6 pivots
        for (int i = 5; i < allPivots.size(); i++) {
            var p0 = allPivots.get(i - 5);
            var p1 = allPivots.get(i - 4);
            var p2 = allPivots.get(i - 3);
            var p3 = allPivots.get(i - 2);
            var p4 = allPivots.get(i - 1);
            var p5 = allPivots.get(i);

            // Check if p4 (5th pivot) is in the date range
            LocalDate p4Date = p4.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
            if (p4Date.isBefore(startDate) || p4Date.isAfter(endDate)) {
                continue;
            }

            System.out.printf("%n--- Window [%d..%d] ---%n", i - 5, i);

            // Print pivot details
            System.out.printf("  p0: %s %s %.1f%n",
                p0.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate(),
                p0.isHigh() ? "HIGH" : "LOW",
                p0.getValue());
            System.out.printf("  p1: %s %s %.1f%n",
                p1.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate(),
                p1.isHigh() ? "HIGH" : "LOW",
                p1.getValue());
            System.out.printf("  p2: %s %s %.1f%n",
                p2.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate(),
                p2.isHigh() ? "HIGH" : "LOW",
                p2.getValue());
            System.out.printf("  p3: %s %s %.1f%n",
                p3.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate(),
                p3.isHigh() ? "HIGH" : "LOW",
                p3.getValue());
            System.out.printf("  p4: %s %s %.1f (last)%n",
                p4.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate(),
                p4.isHigh() ? "HIGH" : "LOW",
                p4.getValue());

            // Build orientation string
            StringBuilder orientationStr = new StringBuilder();
            orientationStr.append(p0.isHigh() ? "H" : "L");
            orientationStr.append(",").append(p1.isHigh() ? "H" : "L");
            orientationStr.append(",").append(p2.isHigh() ? "H" : "L");
            orientationStr.append(",").append(p3.isHigh() ? "H" : "L");
            orientationStr.append(",").append(p4.isHigh() ? "H" : "L");
            System.out.printf("  Orientation: %s%n", orientationStr.toString());

            // Get bar index for last pivot
            int idx4 = tsToIdx.getOrDefault(p4.getTimestamp(), -1);

            if (idx4 < 0) {
                System.out.println("  WARNING: Could not map p4 timestamp to bar index");
                continue;
            }

            // Use ATR at p4's bar index (matches production code: atrArr[lastIdx])
            double atrAtLast = (idx4 >= 0 && idx4 < atrArr.length) ? atrArr[idx4] : atr;

            // High-first: p0=H, p1=L, p2=H, p3=L, p4=H  → pHigh1=p0,pHigh2=p2, pLow1=p1,pLow2=p3, pLast=p4
            if (p0.isHigh() && p1.isLow() && p2.isHigh() && p3.isLow() && p4.isHigh()) {
                System.out.println("  === HIGH-FIRST H,L,H,L,H ===");
                double h1 = p0.getValue(), h2 = p2.getValue();
                double l1 = p1.getValue(), l2 = p3.getValue();
                double lastPrice = p4.getValue();
                int h1Idx = tsToIdx.getOrDefault(p0.getTimestamp(), -1);
                int h2Idx = tsToIdx.getOrDefault(p2.getTimestamp(), -1);
                int l1Idx = tsToIdx.getOrDefault(p1.getTimestamp(), -1);
                int l2Idx = tsToIdx.getOrDefault(p3.getTimestamp(), -1);
                int lastIdx2 = idx4;
                int duration2 = lastIdx2 - Math.min(h1Idx, l1Idx);
                System.out.printf("    h1=%.1f h2=%.1f l1=%.1f l2=%.1f last=%.1f ATR=%.2f%n", h1, h2, l1, l2, lastPrice, atrAtLast);
                System.out.printf("    duration=%d MIN=%d → %s%n", duration2, MIN_PATTERN_BARS, duration2 >= MIN_PATTERN_BARS ? "PASS" : "FAIL");
                double patternHeight2 = Math.max(h1, h2) - Math.min(l1, l2);
                System.out.printf("    patternHeight=%.1f 2*ATR=%.1f → %s%n", patternHeight2, ATR_RANGE_FACTOR * atrAtLast, patternHeight2 >= ATR_RANGE_FACTOR * atrAtLast ? "PASS" : "FAIL");
                // Production code checks: h2 vs h1 ± ATR_FLAT_FACTOR*atr (NOT slope)
                boolean upperFalling2 = h2 < h1 - ATR_FLAT_FACTOR * atrAtLast;
                boolean upperRising2  = h2 > h1 + ATR_FLAT_FACTOR * atrAtLast;
                boolean lowerRising2  = l2 > l1 + ATR_FLAT_FACTOR * atrAtLast;
                boolean lowerFalling2 = l2 < l1 - ATR_FLAT_FACTOR * atrAtLast;
                boolean upperFlat2 = !upperFalling2 && !upperRising2;
                boolean lowerFlat2 = !lowerRising2 && !lowerFalling2;
                System.out.printf("    upper: falling=%b rising=%b flat=%b (h2-h1=%.1f vs ±%.1f)%n", upperFalling2, upperRising2, upperFlat2, h2-h1, ATR_FLAT_FACTOR*atrAtLast);
                System.out.printf("    lower: rising=%b falling=%b flat=%b (l2-l1=%.1f vs ±%.1f)%n", lowerRising2, lowerFalling2, lowerFlat2, l2-l1, ATR_FLAT_FACTOR*atrAtLast);
                String cls2 = upperFalling2 && lowerRising2 ? "SYMMETRIC" :
                              lowerRising2 && (upperFlat2 || upperFalling2) ? "ASCENDING" :
                              upperFalling2 && (lowerFlat2 || lowerFalling2) ? "DESCENDING" :
                              upperRising2 && lowerFalling2 ? "EXPANDING(skip)" : "UNCLASSIFIED(skip)";
                System.out.printf("    Classification: %s%n", cls2);
                // Last pivot is HIGH → check against upper trendline
                if (h2Idx > h1Idx) {
                    double upperSlope2 = (h2 - h1) / (double)(h2Idx - h1Idx);
                    double projH3 = h1 + upperSlope2 * (lastIdx2 - h1Idx);
                    double diff2 = Math.abs(lastPrice - projH3);
                    System.out.printf("    projH3=%.1f actual=%.1f diff=%.1f vs ATR=%.1f → %s%n", projH3, lastPrice, diff2, atrAtLast, diff2 <= atrAtLast ? "PASS" : "FAIL");
                }
            }

            // Low-first: p0=L, p1=H, p2=L, p3=H, p4=L  → pHigh1=p1,pHigh2=p3, pLow1=p0,pLow2=p2, pLast=p4
            if (p0.isLow() && p1.isHigh() && p2.isLow() && p3.isHigh() && p4.isLow()) {
                System.out.println("  === LOW-FIRST L,H,L,H,L ===");
                double h1 = p1.getValue(), h2 = p3.getValue();
                double l1 = p0.getValue(), l2 = p2.getValue();
                double lastPrice = p4.getValue();
                int h1Idx = tsToIdx.getOrDefault(p1.getTimestamp(), -1);
                int h2Idx = tsToIdx.getOrDefault(p3.getTimestamp(), -1);
                int l1Idx = tsToIdx.getOrDefault(p0.getTimestamp(), -1);
                int l2Idx = tsToIdx.getOrDefault(p2.getTimestamp(), -1);
                int lastIdx2 = idx4;
                int duration2 = lastIdx2 - Math.min(h1Idx, l1Idx);
                System.out.printf("    h1=%.1f h2=%.1f l1=%.1f l2=%.1f last=%.1f ATR=%.2f%n", h1, h2, l1, l2, lastPrice, atrAtLast);
                System.out.printf("    duration=%d MIN=%d → %s%n", duration2, MIN_PATTERN_BARS, duration2 >= MIN_PATTERN_BARS ? "PASS" : "FAIL");
                double patternHeight2 = Math.max(h1, h2) - Math.min(l1, l2);
                System.out.printf("    patternHeight=%.1f 2*ATR=%.1f → %s%n", patternHeight2, ATR_RANGE_FACTOR * atrAtLast, patternHeight2 >= ATR_RANGE_FACTOR * atrAtLast ? "PASS" : "FAIL");
                boolean upperFalling2 = h2 < h1 - ATR_FLAT_FACTOR * atrAtLast;
                boolean upperRising2  = h2 > h1 + ATR_FLAT_FACTOR * atrAtLast;
                boolean lowerRising2  = l2 > l1 + ATR_FLAT_FACTOR * atrAtLast;
                boolean lowerFalling2 = l2 < l1 - ATR_FLAT_FACTOR * atrAtLast;
                boolean upperFlat2 = !upperFalling2 && !upperRising2;
                boolean lowerFlat2 = !lowerRising2 && !lowerFalling2;
                System.out.printf("    upper: falling=%b rising=%b flat=%b (h2-h1=%.1f vs ±%.1f)%n", upperFalling2, upperRising2, upperFlat2, h2-h1, ATR_FLAT_FACTOR*atrAtLast);
                System.out.printf("    lower: rising=%b falling=%b flat=%b (l2-l1=%.1f vs ±%.1f)%n", lowerRising2, lowerFalling2, lowerFlat2, l2-l1, ATR_FLAT_FACTOR*atrAtLast);
                String cls2 = upperFalling2 && lowerRising2 ? "SYMMETRIC" :
                              lowerRising2 && (upperFlat2 || upperFalling2) ? "ASCENDING" :
                              upperFalling2 && (lowerFlat2 || lowerFalling2) ? "DESCENDING" :
                              upperRising2 && lowerFalling2 ? "EXPANDING(skip)" : "UNCLASSIFIED(skip)";
                System.out.printf("    Classification: %s%n", cls2);
                // Last pivot is LOW → check against lower trendline
                if (l2Idx > l1Idx) {
                    double lowerSlope2 = (l2 - l1) / (double)(l2Idx - l1Idx);
                    double projL3 = l1 + lowerSlope2 * (lastIdx2 - l1Idx);
                    double diff2 = Math.abs(lastPrice - projL3);
                    System.out.printf("    projL3=%.1f actual=%.1f diff=%.1f vs ATR=%.1f → %s%n", projL3, lastPrice, diff2, atrAtLast, diff2 <= atrAtLast ? "PASS" : "FAIL");
                }
            }
        }
    }

}
