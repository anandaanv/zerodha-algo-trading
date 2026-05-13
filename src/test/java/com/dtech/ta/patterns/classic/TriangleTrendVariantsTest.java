package com.dtech.ta.patterns.classic;

import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.simulation.CandidatePivotZigZag;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.MACDIndicator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Test hypothesis: compare continuation rates under 7 different trend definitions
 * for triangles detected on FNO hourly data.
 *
 * Trend variants:
 * 1. tr_30bar: close[A] >= close[A-30]
 * 2. tr_sma50_pos: close[A] >= SMA50[A]
 * 3. tr_sma50_slope: SMA50[A] >= SMA50[A-10]
 * 4. tr_macd_signal: MACD line >= signal
 * 5. tr_macd_hist: MACD histogram >= 0
 * 6. tr_daily_sma20_pos: daily SMA20; today's close >= SMA20
 * 7. tr_daily_sma20_slope: daily SMA20 rising over 5 days
 */
class TriangleTrendVariantsTest {

    private static final int WINDOW_SIZE = 200;
    private static final int SLIDE_STEP = 100;
    private static final int PRE_TREND_LOOKBACK = 30;
    private static final int BREAKOUT_WINDOW = 30;

    private Path getDataDir() {
        // Try system property first, then env var, then default
        String fromProp = System.getProperty("triangle.data.dir");
        if (fromProp != null) return Paths.get(fromProp);

        String fromEnv = System.getenv("TRIANGLE_DATA_DIR");
        if (fromEnv != null) return Paths.get(fromEnv);

        return Paths.get("/tmp/hourly-scan-bars");
    }

    private Path getOutputDir() {
        String dataDirName = getDataDir().getFileName().toString();
        String outputDirName = "triangle-trend-variants-" + dataDirName;
        return Paths.get("/tmp/" + outputDirName);
    }

    @Test
    void testTriangleTrendVariants() throws IOException {
        Path dataDir = getDataDir();
        Path outputDir = getOutputDir();
        Assumptions.assumeTrue(Files.exists(dataDir), "Hourly scan data missing");

        Files.createDirectories(outputDir);

        String timestamp = Instant.now()
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        List<TrendVariantRecord> allTriangles = new ArrayList<>();
        Set<String> processedSymbols = new HashSet<>();

        try (var stream = Files.newDirectoryStream(dataDir, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                processedSymbols.add(sym);
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < WINDOW_SIZE + 50) continue;

                scanWithSlidingWindow(sym, series, allTriangles);
            }
        }

        // Write CSV
        Path csvOut = outputDir.resolve("triangle-trends-" + timestamp + ".csv");
        writeCsv(csvOut, allTriangles);

        // Analyze and print comparison table
        analyzeAndPrintComparison(allTriangles, csvOut);

        System.out.println("Processed " + processedSymbols.size() + " symbols, "
                + allTriangles.size() + " triangles detected.");
    }

    private void scanWithSlidingWindow(String sym, BarSeries full, List<TrendVariantRecord> allTriangles) {
        Set<String> seen = new HashSet<>();

        for (int windowEnd = WINDOW_SIZE; windowEnd <= full.getEndIndex(); windowEnd += SLIDE_STEP) {
            BarSeries window = sliceUpTo(full, windowEnd);
            List<PivotPoint> pivots = runCpzz(window);

            for (TrianglePattern p : new TriangleClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                String key = sym + "-" + p.kind() + "-" + p.endBarIndex();
                if (seen.contains(key)) continue;
                seen.add(key);

                analyzeTriangleWithTrends(sym, p, full, allTriangles);
            }
        }
    }

    private void analyzeTriangleWithTrends(String sym, TrianglePattern p, BarSeries full,
                                           List<TrendVariantRecord> allTriangles) {
        List<PivotPoint> pivots = p.pivots();
        if (pivots.size() < 6) return;

        PivotPoint A = pivots.get(0);
        PivotPoint B = pivots.get(1);
        PivotPoint C = pivots.get(2);
        PivotPoint D = pivots.get(3);
        PivotPoint E = pivots.get(4);
        PivotPoint F = pivots.get(5);

        int aIdx = A.barIndex();
        int fIdx = F.barIndex();

        if (aIdx < PRE_TREND_LOOKBACK) return;
        if (fIdx + PRE_TREND_LOOKBACK + BREAKOUT_WINDOW + 10 >= full.getBarCount()) return;

        // Break detection
        double closeA = full.getBar(aIdx).getClosePrice().doubleValue();
        double[] upperLine = leastSquaresFit(
                new int[]{aIdx, C.barIndex(), E.barIndex()},
                new double[]{A.price(), C.price(), E.price()},
                full
        );
        double[] lowerLine = leastSquaresFit(
                new int[]{B.barIndex(), D.barIndex(), fIdx},
                new double[]{B.price(), D.price(), F.price()},
                full
        );

        String breakDir = "NO_BREAK";
        int breakBarOffset = -1;

        int maxBreakBar = Math.min(fIdx + BREAKOUT_WINDOW, full.getEndIndex());
        for (int i = fIdx + 1; i <= maxBreakBar; i++) {
            double close = full.getBar(i).getClosePrice().doubleValue();
            double upperProjected = projectLine(upperLine, i, full);
            double lowerProjected = projectLine(lowerLine, i, full);

            if (close > upperProjected) {
                breakDir = "UP";
                breakBarOffset = i - fIdx;
                break;
            } else if (close < lowerProjected) {
                breakDir = "DOWN";
                breakBarOffset = i - fIdx;
                break;
            }
        }

        if (breakDir.equals("NO_BREAK")) return;

        // Compute all 7 trend signals
        boolean tr30bar = computeTr30bar(full, aIdx);
        boolean trSma50Pos = computeTrSma50Pos(full, aIdx);
        boolean trSma50Slope = computeTrSma50Slope(full, aIdx);
        boolean trMacdSignal = computeTrMacdSignal(full, aIdx);
        boolean trMacdHist = computeTrMacdHist(full, aIdx);
        boolean trDailySma20Pos = computeTrDailySma20Pos(full, aIdx);
        boolean trDailySma20Slope = computeTrDailySma20Slope(full, aIdx);

        TrendVariantRecord rec = new TrendVariantRecord(
                sym, p.kind().name(), breakDir, breakBarOffset,
                tr30bar, trSma50Pos, trSma50Slope, trMacdSignal, trMacdHist,
                trDailySma20Pos, trDailySma20Slope,
                A.time(), F.time()
        );
        allTriangles.add(rec);
    }

    private boolean computeTr30bar(BarSeries series, int aIdx) {
        double closeA = series.getBar(aIdx).getClosePrice().doubleValue();
        double closeA30Back = series.getBar(aIdx - 30).getClosePrice().doubleValue();
        return closeA >= closeA30Back;
    }

    private boolean computeTrSma50Pos(BarSeries series, int aIdx) {
        try {
            SMAIndicator sma50 = new SMAIndicator(new ClosePriceIndicator(series), 50);
            if (aIdx < 50) return false;
            double closeA = series.getBar(aIdx).getClosePrice().doubleValue();
            double smaVal = sma50.getValue(aIdx).doubleValue();
            return closeA >= smaVal;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean computeTrSma50Slope(BarSeries series, int aIdx) {
        try {
            SMAIndicator sma50 = new SMAIndicator(new ClosePriceIndicator(series), 50);
            if (aIdx < 50) return false;
            double smaA = sma50.getValue(aIdx).doubleValue();
            double smaA10Back = sma50.getValue(aIdx - 10).doubleValue();
            return smaA >= smaA10Back;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean computeTrMacdSignal(BarSeries series, int aIdx) {
        try {
            MACDIndicator macd = new MACDIndicator(new ClosePriceIndicator(series), 12, 26);
            if (aIdx < 26) return false;
            double macdVal = macd.getValue(aIdx).doubleValue();
            // MACD signal = EMA of MACD with period 9
            SMAIndicator signal = new SMAIndicator(macd, 9);
            if (aIdx < 34) return false;
            double signalVal = signal.getValue(aIdx).doubleValue();
            return macdVal >= signalVal;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean computeTrMacdHist(BarSeries series, int aIdx) {
        try {
            MACDIndicator macd = new MACDIndicator(new ClosePriceIndicator(series), 12, 26);
            if (aIdx < 26) return false;
            double macdVal = macd.getValue(aIdx).doubleValue();
            SMAIndicator signal = new SMAIndicator(macd, 9);
            if (aIdx < 34) return false;
            double signalVal = signal.getValue(aIdx).doubleValue();
            double histogram = macdVal - signalVal;
            return histogram >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean computeTrDailySma20Pos(BarSeries series, int aIdx) {
        try {
            Map<LocalDate, Integer> dailyLastBar = buildDailyIndex(series);
            LocalDate dayA = series.getBar(aIdx).getEndTime().atZone(ZoneId.systemDefault()).toLocalDate();
            Integer barIdxForDay = dailyLastBar.get(dayA);
            if (barIdxForDay == null) return false;

            BarSeries dailySeries = buildDailySeries(series, dailyLastBar);
            int dayBarIdx = -1;
            for (int i = 0; i < dailySeries.getBarCount(); i++) {
                if (dailySeries.getBar(i).getEndTime().atZone(ZoneId.systemDefault()).toLocalDate().equals(dayA)) {
                    dayBarIdx = i;
                    break;
                }
            }
            if (dayBarIdx < 0 || dayBarIdx < 20) return false;

            SMAIndicator dailySma20 = new SMAIndicator(new ClosePriceIndicator(dailySeries), 20);
            double closeToday = dailySeries.getBar(dayBarIdx).getClosePrice().doubleValue();
            double smaVal = dailySma20.getValue(dayBarIdx).doubleValue();
            return closeToday >= smaVal;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean computeTrDailySma20Slope(BarSeries series, int aIdx) {
        try {
            Map<LocalDate, Integer> dailyLastBar = buildDailyIndex(series);
            LocalDate dayA = series.getBar(aIdx).getEndTime().atZone(ZoneId.systemDefault()).toLocalDate();

            BarSeries dailySeries = buildDailySeries(series, dailyLastBar);
            int dayBarIdx = -1;
            for (int i = 0; i < dailySeries.getBarCount(); i++) {
                if (dailySeries.getBar(i).getEndTime().atZone(ZoneId.systemDefault()).toLocalDate().equals(dayA)) {
                    dayBarIdx = i;
                    break;
                }
            }
            if (dayBarIdx < 0 || dayBarIdx < 24) return false; // Need 20 bars + 5 lookback

            SMAIndicator dailySma20 = new SMAIndicator(new ClosePriceIndicator(dailySeries), 20);
            double smaToday = dailySma20.getValue(dayBarIdx).doubleValue();
            double sma5Back = dailySma20.getValue(dayBarIdx - 5).doubleValue();
            return smaToday >= sma5Back;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<LocalDate, Integer> buildDailyIndex(BarSeries series) {
        Map<LocalDate, Integer> result = new HashMap<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            LocalDate date = series.getBar(i).getEndTime().atZone(ZoneId.systemDefault()).toLocalDate();
            result.put(date, i);
        }
        return result;
    }

    private BarSeries buildDailySeries(BarSeries hourly, Map<LocalDate, Integer> dailyIndex) {
        BarSeries dailySeries = new BaseBarSeriesBuilder().withName(hourly.getName()).build();
        Set<LocalDate> processed = new HashSet<>();

        for (int i = 0; i < hourly.getBarCount(); i++) {
            LocalDate date = hourly.getBar(i).getEndTime().atZone(ZoneId.systemDefault()).toLocalDate();
            if (!processed.contains(date)) {
                Integer lastBarIdx = dailyIndex.get(date);
                if (lastBarIdx != null) {
                    Bar hourlyBar = hourly.getBar(lastBarIdx);
                    ZonedDateTime endOfDay = date.atTime(23, 59, 59).atZone(ZoneId.systemDefault());
                    // Create daily bar using hourly bar's close as daily close
                    dailySeries.addBar(BarsLoader.getBar(
                            hourlyBar.getOpenPrice().doubleValue(),
                            hourlyBar.getHighPrice().doubleValue(),
                            hourlyBar.getLowPrice().doubleValue(),
                            hourlyBar.getClosePrice().doubleValue(),
                            hourlyBar.getVolume().longValue(),
                            endOfDay.toInstant(),
                            Duration.ofDays(1)
                    ));
                    processed.add(date);
                }
            }
        }
        return dailySeries;
    }

    private double[] leastSquaresFit(int[] barIndices, double[] prices, BarSeries series) {
        double n = barIndices.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        for (int i = 0; i < barIndices.length; i++) {
            double x = barIndices[i];
            double y = prices[i];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        double intercept = (sumY - slope * sumX) / n;

        return new double[]{slope, intercept};
    }

    private double projectLine(double[] line, int barIndex, BarSeries series) {
        return line[0] * barIndex + line[1];
    }

    private BarSeries sliceUpTo(BarSeries full, int endIndex) {
        BarSeries window = new BaseBarSeriesBuilder().withName(full.getName()).build();
        for (int i = 0; i <= endIndex && i <= full.getEndIndex(); i++) {
            window.addBar(full.getBar(i));
        }
        return window;
    }

    private List<PivotPoint> runCpzz(BarSeries series) {
        ZigZagParams params = ZigZagParams.ofDefaults(
                14, 1.0, 0.005, 1.0, 1, false, 1.0, 14, ZigZagParams.Mode.BACKTEST);
        CandidatePivotZigZag cpzz = new CandidatePivotZigZag(params);
        for (int i = 0; i < series.getBarCount(); i++) cpzz.processBar(series, i);
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

    private BarSeries loadCsv(String sym, Path csv) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(sym).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;
            String ts = parts[0];
            if (!ts.endsWith("Z")) ts += "Z";
            series.addBar(BarsLoader.getBar(
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]), Instant.parse(ts), Duration.ofHours(1)));
        }
        return series;
    }

    private void writeCsv(Path csvOut, List<TrendVariantRecord> records) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("symbol,kind,break_dir,break_bar_offset,")
                .append("tr_30bar,tr_sma50_pos,tr_sma50_slope,tr_macd_signal,tr_macd_hist,")
                .append("tr_daily_sma20_pos,tr_daily_sma20_slope,start_time,end_time\n");

        for (TrendVariantRecord r : records) {
            sb.append(r.symbol).append(",")
                    .append(r.kind).append(",")
                    .append(r.breakDir).append(",")
                    .append(r.breakBarOffset).append(",")
                    .append(r.tr30bar ? "TRUE" : "FALSE").append(",")
                    .append(r.trSma50Pos ? "TRUE" : "FALSE").append(",")
                    .append(r.trSma50Slope ? "TRUE" : "FALSE").append(",")
                    .append(r.trMacdSignal ? "TRUE" : "FALSE").append(",")
                    .append(r.trMacdHist ? "TRUE" : "FALSE").append(",")
                    .append(r.trDailySma20Pos ? "TRUE" : "FALSE").append(",")
                    .append(r.trDailySma20Slope ? "TRUE" : "FALSE").append(",")
                    .append(r.startTime).append(",")
                    .append(r.endTime).append("\n");
        }

        Files.writeString(csvOut, sb.toString());
    }

    private void analyzeAndPrintComparison(List<TrendVariantRecord> records, Path csvOut) throws IOException {
        String[] variants = {
                "tr_30bar", "tr_sma50_pos", "tr_sma50_slope", "tr_macd_signal", "tr_macd_hist",
                "tr_daily_sma20_pos", "tr_daily_sma20_slope"
        };

        Map<String, TrendStats> stats = new HashMap<>();

        for (String variant : variants) {
            stats.put(variant, new TrendStats());
        }

        // Analyze each variant
        for (TrendVariantRecord r : records) {
            boolean tr30bar = r.tr30bar;
            boolean trSma50Pos = r.trSma50Pos;
            boolean trSma50Slope = r.trSma50Slope;
            boolean trMacdSignal = r.trMacdSignal;
            boolean trMacdHist = r.trMacdHist;
            boolean trDailySma20Pos = r.trDailySma20Pos;
            boolean trDailySma20Slope = r.trDailySma20Slope;

            processVariant(stats.get("tr_30bar"), tr30bar, r.breakDir);
            processVariant(stats.get("tr_sma50_pos"), trSma50Pos, r.breakDir);
            processVariant(stats.get("tr_sma50_slope"), trSma50Slope, r.breakDir);
            processVariant(stats.get("tr_macd_signal"), trMacdSignal, r.breakDir);
            processVariant(stats.get("tr_macd_hist"), trMacdHist, r.breakDir);
            processVariant(stats.get("tr_daily_sma20_pos"), trDailySma20Pos, r.breakDir);
            processVariant(stats.get("tr_daily_sma20_slope"), trDailySma20Slope, r.breakDir);
        }

        // Print comparison table
        System.out.println("\n=== TREND VARIANT COMPARISON (n=" + records.size() + " triangles) ===");
        System.out.println(String.format("%-25s | %14s | %19s | %9s",
                "Trend definition", "overall cont%", "bullish-trend cont%", "n bullish"));
        System.out.println("-".repeat(75));

        String bestVariant = null;
        double bestBullishCont = -1;

        for (String variant : variants) {
            TrendStats s = stats.get(variant);
            double overallCont = s.totalWithBreak > 0 ? (100.0 * s.continuation / s.totalWithBreak) : 0;
            double bullishCont = s.bullishTotal > 0 ? (100.0 * s.bullishContinuation / s.bullishTotal) : 0;

            System.out.println(String.format("%-25s | %13.1f%% | %18.1f%% | %9d",
                    variant, overallCont, bullishCont, s.bullishTotal));

            if (bullishCont > bestBullishCont) {
                bestBullishCont = bullishCont;
                bestVariant = variant;
            }
        }

        System.out.println();
        System.out.println("Best by bullish-trend continuation %: " + bestVariant + " (" + String.format("%.1f%%", bestBullishCont) + ")");
        System.out.println();
        System.out.println("CSV written to: " + csvOut);
        System.out.println("Total rows: " + records.size());
    }

    private void processVariant(TrendStats stats, boolean isBullish, String breakDir) {
        stats.totalWithBreak++;
        if (isBullish) {
            stats.bullishTotal++;
            if (breakDir.equals("UP")) {
                stats.continuation++;
                stats.bullishContinuation++;
            }
        } else {
            if (breakDir.equals("DOWN")) {
                stats.continuation++;
            }
        }
    }

    static class TrendStats {
        int totalWithBreak = 0;
        int continuation = 0;
        int bullishTotal = 0;
        int bullishContinuation = 0;
    }

    static class TrendVariantRecord {
        String symbol, kind, breakDir;
        int breakBarOffset;
        boolean tr30bar, trSma50Pos, trSma50Slope, trMacdSignal, trMacdHist;
        boolean trDailySma20Pos, trDailySma20Slope;
        Instant startTime, endTime;

        TrendVariantRecord(String symbol, String kind, String breakDir, int breakBarOffset,
                          boolean tr30bar, boolean trSma50Pos, boolean trSma50Slope,
                          boolean trMacdSignal, boolean trMacdHist, boolean trDailySma20Pos,
                          boolean trDailySma20Slope, Instant startTime, Instant endTime) {
            this.symbol = symbol;
            this.kind = kind;
            this.breakDir = breakDir;
            this.breakBarOffset = breakBarOffset;
            this.tr30bar = tr30bar;
            this.trSma50Pos = trSma50Pos;
            this.trSma50Slope = trSma50Slope;
            this.trMacdSignal = trMacdSignal;
            this.trMacdHist = trMacdHist;
            this.trDailySma20Pos = trDailySma20Pos;
            this.trDailySma20Slope = trDailySma20Slope;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }
}
