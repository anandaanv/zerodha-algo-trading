package com.dtech.ta.patterns.classic;

import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagPoint.Type;
import com.dtech.kitecon.elliott.ImpulseFeatureExtractor;
import com.dtech.kitecon.elliott.IndicatorBundle;
import com.dtech.kitecon.simulation.CandidatePivotZigZag;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractDtbTrainingDataTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final Path OUTPUT_DIR = Paths.get("/tmp/dtb-ml-training");
    private static final double RETEST_TOLERANCE_PCT = 0.5;
    private static final int MAX_BARS_TO_BREAKOUT = 30;
    private static final int MAX_BARS_TO_RETEST = 30;
    private static final int TIME_STOP_BARS = 30;
    private static final int WINDOW_SIZE = 200;
    private static final int SLIDE_STEP = 100;

    @Test
    void extractDtbTrainingData() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing at " + DATA_DIR);

        Files.createDirectories(OUTPUT_DIR);

        String timestamp = Instant.now()
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path csvPath = OUTPUT_DIR.resolve("dtb-retest-train-" + timestamp + ".csv");

        List<String> csvLines = new ArrayList<>();
        csvLines.add(buildCsvHeader());

        // Diagnostic counters
        java.util.concurrent.atomic.AtomicInteger totalDetected = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger noBreakout = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger noRetest = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger lateDetection = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger badGeometry = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger entered = new java.util.concurrent.atomic.AtomicInteger(0);

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path symFile : stream) {
                String sym = symFile.getFileName().toString().replace(".csv", "");
                BarSeries series = loadCsv(sym, symFile);
                if (series.getBarCount() < WINDOW_SIZE + 50) continue;

                scanWithSlidingWindow(sym, series, csvLines, totalDetected, noBreakout, noRetest, lateDetection, badGeometry, entered);
            }
        }

        Files.write(csvPath, csvLines, StandardCharsets.UTF_8);
        System.out.println("Diagnostic: detected=" + totalDetected.get()
                + " noBreakout=" + noBreakout.get()
                + " noRetest=" + noRetest.get()
                + " lateDetection=" + lateDetection.get()
                + " badGeometry=" + badGeometry.get()
                + " entered=" + entered.get());
        System.out.println("Extracted " + (csvLines.size() - 1) + " trades to " + csvPath);
    }

    private void scanWithSlidingWindow(String sym, BarSeries full,
                                        List<String> csvLines,
                                        java.util.concurrent.atomic.AtomicInteger totalDetected,
                                        java.util.concurrent.atomic.AtomicInteger noBreakout,
                                        java.util.concurrent.atomic.AtomicInteger noRetest,
                                        java.util.concurrent.atomic.AtomicInteger lateDetection,
                                        java.util.concurrent.atomic.AtomicInteger badGeometry,
                                        java.util.concurrent.atomic.AtomicInteger entered) {
        Set<String> seen = new HashSet<>();

        // Sliding window: extract pivots per window, detect patterns
        for (int windowEnd = WINDOW_SIZE; windowEnd <= full.getEndIndex(); windowEnd += SLIDE_STEP) {
            BarSeries window = sliceUpTo(full, windowEnd);
            List<PivotPoint> pivots = runCpzz(window);

            // Double Top patterns (bearish, SHORT direction)
            for (DoubleTopPattern p : new DoubleTopClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                String key = sym + "-DT-" + p.endBarIndex();
                if (seen.contains(key)) continue;
                seen.add(key);
                totalDetected.incrementAndGet();
                extractAndCollect(sym, p, pivots, full, false, csvLines, noBreakout, noRetest, lateDetection, badGeometry, entered);
            }

            // Double Bottom patterns (bullish, LONG direction)
            for (DoubleBottomPattern p : new DoubleBottomClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                String key = sym + "-DB-" + p.endBarIndex();
                if (seen.contains(key)) continue;
                seen.add(key);
                totalDetected.incrementAndGet();
                extractAndCollect(sym, p, pivots, full, true, csvLines, noBreakout, noRetest, lateDetection, badGeometry, entered);
            }
        }
    }

    private BarSeries sliceUpTo(BarSeries full, int endIndex) {
        BarSeries window = new BaseBarSeriesBuilder().withName(full.getName()).build();
        for (int i = 0; i <= endIndex && i <= full.getEndIndex(); i++) {
            window.addBar(full.getBar(i));
        }
        return window;
    }

    private void extractAndCollect(String sym, Object patternObj, List<PivotPoint> pivots,
                                    BarSeries full, boolean bullishPattern,
                                    List<String> csvLines,
                                    java.util.concurrent.atomic.AtomicInteger noBreakout,
                                    java.util.concurrent.atomic.AtomicInteger noRetest,
                                    java.util.concurrent.atomic.AtomicInteger lateDetection,
                                    java.util.concurrent.atomic.AtomicInteger badGeometry,
                                    java.util.concurrent.atomic.AtomicInteger entered) {
        // Extract pivots based on pattern type
        List<PivotPoint> patternPivots;
        double atr;
        int eBar;

        if (patternObj instanceof DoubleTopPattern) {
            DoubleTopPattern p = (DoubleTopPattern) patternObj;
            patternPivots = p.pivots();
            atr = p.atr();
            eBar = p.endBarIndex();
        } else if (patternObj instanceof DoubleBottomPattern) {
            DoubleBottomPattern p = (DoubleBottomPattern) patternObj;
            patternPivots = p.pivots();
            atr = p.atr();
            eBar = p.endBarIndex();
        } else {
            return;
        }

        if (patternPivots.size() < 4) return;

        // Need room for breakout (30 bars) + retest (30 bars) + exit simulation (30 bars) + margin
        if (eBar + MAX_BARS_TO_BREAKOUT + MAX_BARS_TO_RETEST + TIME_STOP_BARS + 10 >= full.getBarCount()) {
            lateDetection.incrementAndGet();
            return;
        }

        double aPrice = patternPivots.get(0).price();
        double bPrice = patternPivots.get(1).price();  // neckline
        double cPrice = patternPivots.get(2).price();
        double dPrice = patternPivots.get(3).price();
        double neckline = bPrice;  // B IS the neckline for DTB
        double height = Math.abs(aPrice - neckline);

        // Breakout
        int breakoutBar = -1;
        int maxB = Math.min(eBar + MAX_BARS_TO_BREAKOUT, full.getEndIndex());
        for (int i = eBar + 1; i <= maxB; i++) {
            double c = full.getBar(i).getClosePrice().doubleValue();
            if ((bullishPattern && c > neckline) || (!bullishPattern && c < neckline)) {
                breakoutBar = i;
                break;
            }
        }
        if (breakoutBar < 0) {
            noBreakout.incrementAndGet();
            return;
        }

        // Retest
        int retestBar = -1;
        double tolerance = neckline * RETEST_TOLERANCE_PCT / 100.0;
        int maxR = Math.min(breakoutBar + MAX_BARS_TO_RETEST, full.getEndIndex());
        for (int i = breakoutBar + 1; i <= maxR; i++) {
            Bar bar = full.getBar(i);
            if (bar.getLowPrice().doubleValue() <= neckline + tolerance
                    && bar.getHighPrice().doubleValue() >= neckline - tolerance) {
                retestBar = i;
                break;
            }
        }
        if (retestBar < 0) {
            noRetest.incrementAndGet();
            return;
        }

        double entry = full.getBar(retestBar).getClosePrice().doubleValue();
        double target = bullishPattern ? neckline + height : neckline - height;

        // 1-OTM SL placement
        double stop;
        if (bullishPattern) {
            // LONG, DB: stop below min(A,C) by 1 ATR
            stop = Math.min(aPrice, cPrice) - atr;
        } else {
            // SHORT, DT: stop above max(A,C) by 1 ATR
            stop = Math.max(aPrice, cPrice) + atr;
        }

        // Geometry sanity
        if (bullishPattern && (stop >= entry || target <= entry)) {
            badGeometry.incrementAndGet();
            return;
        }
        if (!bullishPattern && (stop <= entry || target >= entry)) {
            badGeometry.incrementAndGet();
            return;
        }

        // Simulate trade
        int exitBar = -1;
        double exitPrice = entry;
        String exitReason = "TIMEOUT";
        int maxBar = Math.min(retestBar + TIME_STOP_BARS, full.getEndIndex());
        for (int i = retestBar + 1; i <= maxBar; i++) {
            Bar bar = full.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double lo = bar.getLowPrice().doubleValue();
            double cl = bar.getClosePrice().doubleValue();
            if (bullishPattern) {
                boolean stopHit = (cl <= stop);
                if (stopHit) {
                    exitBar = i;
                    exitPrice = cl;
                    exitReason = "STOP";
                    break;
                }
                if (hi >= target) {
                    exitBar = i;
                    exitPrice = target;
                    exitReason = "TARGET";
                    break;
                }
            } else {
                boolean stopHit = (cl >= stop);
                if (stopHit) {
                    exitBar = i;
                    exitPrice = cl;
                    exitReason = "STOP";
                    break;
                }
                if (lo <= target) {
                    exitBar = i;
                    exitPrice = target;
                    exitReason = "TARGET";
                    break;
                }
            }
        }
        if (exitBar < 0) {
            exitBar = maxBar;
            exitPrice = full.getBar(maxBar).getClosePrice().doubleValue();
        }

        double pnl = bullishPattern ? (exitPrice - entry) / entry * 100 : (entry - exitPrice) / entry * 100;
        boolean wasWinner = pnl > 0;
        int holdingBars = exitBar - retestBar;

        // Extract features at retest bar
        try {
            entered.incrementAndGet();
            Instant entryTimestamp = full.getBar(retestBar).getEndTime();
            double[] features = extractFeaturesAtBar(sym, full, retestBar);

            // Build CSV row
            StringBuilder row = new StringBuilder();
            row.append(entryTimestamp).append(",");
            row.append(entry).append(",");
            row.append(wasWinner ? "1" : "0").append(",");
            row.append(bullishPattern ? "1" : "-1").append(",");
            row.append(sym).append(",");
            row.append("OneHour").append(",");
            row.append(wasWinner ? "1" : "0").append(",");
            row.append(String.format("%.2f", pnl)).append(",");
            row.append(exitReason).append(",");
            row.append(holdingBars);

            for (double f : features) {
                row.append(",");
                if (Double.isNaN(f)) {
                    row.append("");
                } else {
                    row.append(String.format("%.6f", f));
                }
            }

            csvLines.add(row.toString());
        } catch (Exception e) {
            System.err.println("Failed to extract features for " + sym + ": " + e.getMessage());
        }
    }

    private double[] extractFeaturesAtBar(String sym, BarSeries series, int barIdx) {
        // Precompute indicators
        IndicatorBundle bundle = new IndicatorBundle(series);
        Map<Integer, ImpulseFeatureExtractor.IndicatorSnapshot> indicators = bundle.updateAndGetSnapshots();

        // Build timestamp-to-index mapping
        Map<Instant, Integer> tsToIdx = new HashMap<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            tsToIdx.put(series.getBar(i).getEndTime(), i);
        }

        // Run CPZZ to get pivots
        ZigZagParams params = ZigZagParams.ofDefaults(
                14, 1.0, 0.005, 1.0, 1, false, 1.0, 14, ZigZagParams.Mode.BACKTEST);
        CandidatePivotZigZag cpzz = new CandidatePivotZigZag(params);
        for (int i = 0; i < series.getBarCount(); i++) {
            cpzz.processBar(series, i);
        }

        List<ZigZagPoint> zigzagPivots = new ArrayList<>(cpzz.getConfirmedPivots());
        zigzagPivots.sort(Comparator.comparing(ZigZagPoint::getTimestamp));

        // Find pivot index corresponding to retest bar
        Instant retestTime = series.getBar(barIdx).getEndTime();
        int pivotIdx = -1;
        for (int i = 0; i < zigzagPivots.size(); i++) {
            if (zigzagPivots.get(i).getTimestamp().equals(retestTime)) {
                pivotIdx = i;
                break;
            }
        }

        // If exact match not found, use most recent pivot before retest bar
        if (pivotIdx < 0) {
            for (int i = zigzagPivots.size() - 1; i >= 0; i--) {
                if (!zigzagPivots.get(i).getTimestamp().isAfter(retestTime)) {
                    pivotIdx = i;
                    break;
                }
            }
        }

        // Fallback: create synthetic pivot at retest bar
        if (pivotIdx < 0) {
            ZigZagPoint synthetic = ZigZagPoint.builder()
                    .type(ZigZagPoint.Type.HIGH)
                    .timestamp(retestTime)
                    .barIndex(barIdx)
                    .sequence(retestTime.getEpochSecond())
                    .value(series.getBar(barIdx).getClosePrice().doubleValue())
                    .atrAtPivot(0.0)
                    .legSizePct(0.0)
                    .legDurationBars(0.0)
                    .legSpeed(0.0)
                    .retracementPct(0.0)
                    .build();
            zigzagPivots.add(synthetic);
            pivotIdx = zigzagPivots.size() - 1;
        }

        // Extract features (HTF, LTF inputs are null — extractor will fill NaN)
        ImpulseFeatureExtractor extractor = new ImpulseFeatureExtractor(null);
        double[] features = extractor.extractFeatures(
                zigzagPivots,
                pivotIdx,
                series,
                indicators,
                null, null,
                new ArrayList<>(),
                tsToIdx,
                null, null,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>()
        );

        return features;
    }

    private String buildCsvHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp,price,label,direction,symbol,timeframe,was_winner,pnl_pct,exit_reason,holding_bars");
        for (int i = 0; i < 395; i++) {
            sb.append(",f").append(String.format("%03d", i));
        }
        return sb.toString();
    }

    private boolean extractWasWinner(String csvLine) {
        String[] parts = csvLine.split(",");
        if (parts.length < 7) return false;
        return "1".equals(parts[6]);
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
}
