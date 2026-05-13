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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Test hypothesis: "If volume is intact during a triangle formation,
 * the triangle breaks in the same direction as the trend preceding it."
 *
 * Scans 253 FNO hourly CSVs with sliding window (200 bars, slide 100),
 * analyzes pre-trend direction + volume ratio + break direction.
 * Outputs CSV and cross-tab summary to stdout.
 */
class TriangleVolumeHypothesisTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final Path OUTPUT_DIR = Paths.get("/tmp/triangle-hypothesis");
    private static final int WINDOW_SIZE = 200;
    private static final int SLIDE_STEP = 100;
    private static final int PRE_TREND_LOOKBACK = 30;
    private static final int BREAKOUT_WINDOW = 30;
    private static final double INTACT_VOLUME_THRESHOLD = 0.7;

    @Test
    void testTriangleVolumeHypothesis() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        // Create output directory
        Files.createDirectories(OUTPUT_DIR);

        // Generate run ID with timestamp
        String timestamp = Instant.now()
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        List<TriangleRecord> allTriangles = new ArrayList<>();
        Set<String> processedSymbols = new HashSet<>();

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                processedSymbols.add(sym);
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < WINDOW_SIZE + 50) continue;

                scanWithSlidingWindow(sym, series, allTriangles);
            }
        }

        // Write CSV
        Path csvOut = OUTPUT_DIR.resolve("triangle-vol-" + timestamp + ".csv");
        writeCsv(csvOut, allTriangles);

        // Compute cross-tab
        analyzeAndPrintSummary(allTriangles, csvOut);

        System.out.println("Processed " + processedSymbols.size() + " symbols, "
                + allTriangles.size() + " triangles detected.");
    }

    private void scanWithSlidingWindow(String sym, BarSeries full, List<TriangleRecord> allTriangles) {
        Set<String> seen = new HashSet<>();

        // Sliding window: window=200, slide=100
        for (int windowEnd = WINDOW_SIZE; windowEnd <= full.getEndIndex(); windowEnd += SLIDE_STEP) {
            BarSeries window = sliceUpTo(full, windowEnd);
            List<PivotPoint> pivots = runCpzz(window);

            // Detect triangles
            for (TrianglePattern p : new TriangleClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                String key = sym + "-" + p.kind() + "-" + p.endBarIndex();
                if (seen.contains(key)) continue;
                seen.add(key);

                // Analyze this triangle
                analyzeTriangle(sym, p, full, allTriangles);
            }
        }
    }

    private void analyzeTriangle(String sym, TrianglePattern p, BarSeries full,
                                  List<TriangleRecord> allTriangles) {
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

        // Skip if A is in first 30 bars (no pre-trend data)
        if (aIdx < PRE_TREND_LOOKBACK) return;

        // Skip if F is within 60 bars of series end (no breakout room)
        if (fIdx + PRE_TREND_LOOKBACK + BREAKOUT_WINDOW + 10 >= full.getBarCount()) return;

        // Pre-trend direction
        double closeA = full.getBar(aIdx).getClosePrice().doubleValue();
        double closeA30Back = full.getBar(aIdx - PRE_TREND_LOOKBACK).getClosePrice().doubleValue();
        double preTrendPct = (closeA - closeA30Back) / closeA30Back * 100.0;
        String preTrendDir = preTrendPct >= 0 ? "BULLISH" : "BEARISH";

        // Volume ratio
        long volTriangle = 0, volPreTrend = 0;
        int countTriangle = 0, countPreTrend = 0;

        for (int i = aIdx; i <= fIdx; i++) {
            volTriangle += full.getBar(i).getVolume().longValue();
            countTriangle++;
        }

        for (int i = aIdx - PRE_TREND_LOOKBACK; i < aIdx; i++) {
            volPreTrend += full.getBar(i).getVolume().longValue();
            countPreTrend++;
        }

        if (volPreTrend == 0) return;

        double avgVolTriangle = (double) volTriangle / countTriangle;
        double avgVolPreTrend = (double) volPreTrend / countPreTrend;
        double volRatio = avgVolTriangle / avgVolPreTrend;
        String volBucket = volRatio >= INTACT_VOLUME_THRESHOLD ? "INTACT" : "DECLINING";

        // Trendline construction: upper = (A, C, E), lower = (B, D, F)
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

        // Break detection: scan 30 bars after F
        String breakDir = "NO_BREAK";
        int breakBarOffset = -1;
        double breakClosePct = 0;

        int maxBreakBar = Math.min(fIdx + BREAKOUT_WINDOW, full.getEndIndex());
        for (int i = fIdx + 1; i <= maxBreakBar; i++) {
            double close = full.getBar(i).getClosePrice().doubleValue();
            double upperProjected = projectLine(upperLine, i, full);
            double lowerProjected = projectLine(lowerLine, i, full);

            if (close > upperProjected) {
                breakDir = "UP";
                breakBarOffset = i - fIdx;
                breakClosePct = (close - closeA) / closeA * 100.0;
                break;
            } else if (close < lowerProjected) {
                breakDir = "DOWN";
                breakBarOffset = i - fIdx;
                breakClosePct = (closeA - close) / closeA * 100.0;
                break;
            }
        }

        // Continuation
        boolean continuation = false;
        if (!breakDir.equals("NO_BREAK")) {
            continuation = (preTrendDir.equals("BULLISH") && breakDir.equals("UP"))
                    || (preTrendDir.equals("BEARISH") && breakDir.equals("DOWN"));
        }

        TriangleRecord rec = new TriangleRecord(
                sym, p.kind().name(), preTrendDir, preTrendPct,
                volRatio, volBucket, breakDir, breakBarOffset, breakClosePct,
                continuation, A.time(), F.time()
        );
        allTriangles.add(rec);
    }

    private double[] leastSquaresFit(int[] barIndices, double[] prices, BarSeries series) {
        // Simple least-squares: returns [slope, intercept]
        // line = slope * barIndex + intercept
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

    private void writeCsv(Path csvOut, List<TriangleRecord> records) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("symbol,kind,pre_trend_dir,pre_trend_pct,vol_ratio,vol_bucket,")
                .append("break_dir,break_bar_offset,break_close_pct,continuation,start_time,end_time\n");

        for (TriangleRecord r : records) {
            sb.append(r.symbol).append(",")
                    .append(r.kind).append(",")
                    .append(r.preTrendDir).append(",")
                    .append(String.format("%.2f", r.preTrendPct)).append(",")
                    .append(String.format("%.3f", r.volRatio)).append(",")
                    .append(r.volBucket).append(",")
                    .append(r.breakDir).append(",")
                    .append(r.breakBarOffset).append(",")
                    .append(String.format("%.2f", r.breakClosePct)).append(",")
                    .append(r.continuation ? "TRUE" : "FALSE").append(",")
                    .append(r.startTime).append(",")
                    .append(r.endTime).append("\n");
        }

        Files.writeString(csvOut, sb.toString());
    }

    private void analyzeAndPrintSummary(List<TriangleRecord> records, Path csvOut) throws IOException {
        // Filter out NO_BREAK
        List<TriangleRecord> withBreak = records.stream()
                .filter(r -> !r.breakDir.equals("NO_BREAK"))
                .toList();

        // Cross-tab: [preTrendDir][volBucket][breakDir]
        Map<String, Integer> bullishIntact = new HashMap<>();
        Map<String, Integer> bullishDeclining = new HashMap<>();
        Map<String, Integer> bearishIntact = new HashMap<>();
        Map<String, Integer> bearishDeclining = new HashMap<>();

        for (TriangleRecord r : withBreak) {
            Map<String, Integer> bucket = null;

            if (r.preTrendDir.equals("BULLISH") && r.volBucket.equals("INTACT")) {
                bucket = bullishIntact;
            } else if (r.preTrendDir.equals("BULLISH") && r.volBucket.equals("DECLINING")) {
                bucket = bullishDeclining;
            } else if (r.preTrendDir.equals("BEARISH") && r.volBucket.equals("INTACT")) {
                bucket = bearishIntact;
            } else if (r.preTrendDir.equals("BEARISH") && r.volBucket.equals("DECLINING")) {
                bucket = bearishDeclining;
            }

            if (bucket != null) {
                bucket.merge(r.breakDir, 1, Integer::sum);
            }
        }

        // Print cross-tab
        int bullishIntactUp = bullishIntact.getOrDefault("UP", 0);
        int bullishIntactDn = bullishIntact.getOrDefault("DOWN", 0);
        int bullishDeclUp = bullishDeclining.getOrDefault("UP", 0);
        int bullishDeclDn = bullishDeclining.getOrDefault("DOWN", 0);
        int bearishIntactUp = bearishIntact.getOrDefault("UP", 0);
        int bearishIntactDn = bearishIntact.getOrDefault("DOWN", 0);
        int bearishDeclUp = bearishDeclining.getOrDefault("UP", 0);
        int bearishDeclDn = bearishDeclining.getOrDefault("DOWN", 0);

        System.out.println("\n=== TRIANGLE VOLUME HYPOTHESIS TEST ===");
        System.out.println("Total triangles detected: " + records.size());
        System.out.println("Triangles with breakout (excl NO_BREAK): " + withBreak.size());
        System.out.println();
        System.out.println("                    | Vol INTACT      |  Vol DECLINING");
        System.out.println("Pre-trend BULLISH   | up: " + bullishIntactUp + " dn: " + bullishIntactDn
                + " | up: " + bullishDeclUp + " dn: " + bullishDeclDn);
        System.out.println("Pre-trend BEARISH   | up: " + bearishIntactUp + " dn: " + bearishIntactDn
                + " | up: " + bearishDeclUp + " dn: " + bearishDeclDn);
        System.out.println();

        int intactContinuation = bullishIntactUp + bearishIntactDn;
        int intactTotal = bullishIntactUp + bullishIntactDn + bearishIntactUp + bearishIntactDn;
        int declContinuation = bullishDeclUp + bearishDeclDn;
        int declTotal = bullishDeclUp + bullishDeclDn + bearishDeclUp + bearishDeclDn;

        double pIntact = intactTotal > 0 ? (100.0 * intactContinuation / intactTotal) : 0;
        double pDecl = declTotal > 0 ? (100.0 * declContinuation / declTotal) : 0;

        System.out.println("P(continuation | INTACT)    = (" + intactContinuation + " / " + intactTotal
                + ") = " + String.format("%.1f%%", pIntact));
        System.out.println("P(continuation | DECLINING) = (" + declContinuation + " / " + declTotal
                + ") = " + String.format("%.1f%%", pDecl));
        System.out.println();

        double diff = pIntact - pDecl;
        String verdict = diff >= 10.0 ? "SUPPORTED (diff >= 10pp)" : "REJECTED (diff < 10pp)";
        System.out.println("Hypothesis verdict: " + verdict + " (diff = " + String.format("%.1f", diff) + "pp)");
        System.out.println();
        System.out.println("CSV written to: " + csvOut);
        System.out.println("Total rows: " + records.size());
    }

    // Data class for triangle record
    static class TriangleRecord {
        String symbol, kind, preTrendDir, volBucket, breakDir;
        double preTrendPct, volRatio, breakClosePct;
        int breakBarOffset;
        boolean continuation;
        Instant startTime, endTime;

        TriangleRecord(String symbol, String kind, String preTrendDir, double preTrendPct,
                       double volRatio, String volBucket, String breakDir, int breakBarOffset,
                       double breakClosePct, boolean continuation, Instant startTime, Instant endTime) {
            this.symbol = symbol;
            this.kind = kind;
            this.preTrendDir = preTrendDir;
            this.preTrendPct = preTrendPct;
            this.volRatio = volRatio;
            this.volBucket = volBucket;
            this.breakDir = breakDir;
            this.breakBarOffset = breakBarOffset;
            this.breakClosePct = breakClosePct;
            this.continuation = continuation;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }
}
