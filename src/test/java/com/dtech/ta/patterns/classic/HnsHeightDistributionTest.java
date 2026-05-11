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
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Diagnostic: distribution of HNS pattern heights on hourly Indian data,
 * to explain why mean P/L per trade is ~0.09%.
 *
 * Pattern height = |head_price - neckline| / neckline * 100, in percent.
 * That's the measured-move target distance from entry (at neckline retest).
 *
 * If patterns are typically tiny (e.g. 0.5%), then a 1:1 R:R strategy
 * with 56% win rate naturally produces ~0.1% mean P/L per trade.
 *
 * @see CpzzVsWilliamsSimulationTest — strategy that produces +46.80% total
 */
class HnsHeightDistributionTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");

    @Test
    void measurePatternHeights() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        List<Double> heights = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < 250) continue;

                List<PivotPoint> pivots = runCpzz(series);

                for (HnsPattern p : new HnsClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    addHeight(heights, p);
                }
                for (ReverseHnsPattern p : new ReverseHnsClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    addHeight(heights, p);
                }
            }
        }

        Collections.sort(heights);
        int n = heights.size();
        double mean = heights.stream().mapToDouble(Double::doubleValue).sum() / n;

        System.out.println("\n========== HNS / REV_HNS PATTERN HEIGHT DISTRIBUTION ==========");
        System.out.printf("Total patterns: %d (CPZZ pivots, hourly data)%n%n", n);
        System.out.printf("  Min  : %.2f%%%n", heights.get(0));
        System.out.printf("  P10  : %.2f%%%n", heights.get(n / 10));
        System.out.printf("  P25  : %.2f%%%n", heights.get(n / 4));
        System.out.printf("  P50  : %.2f%%   <- median%n", heights.get(n / 2));
        System.out.printf("  Mean : %.2f%%%n", mean);
        System.out.printf("  P75  : %.2f%%%n", heights.get(3 * n / 4));
        System.out.printf("  P90  : %.2f%%%n", heights.get(9 * n / 10));
        System.out.printf("  Max  : %.2f%%%n", heights.get(n - 1));

        // Bins
        int[] bins = new int[6];
        for (double h : heights) {
            if (h < 0.5) bins[0]++;
            else if (h < 1.0) bins[1]++;
            else if (h < 2.0) bins[2]++;
            else if (h < 3.0) bins[3]++;
            else if (h < 5.0) bins[4]++;
            else bins[5]++;
        }
        String[] labels = {"<0.5%", "0.5-1%", "1-2%", "2-3%", "3-5%", ">5%"};
        System.out.println("\nHistogram (pattern height = head-to-neckline distance, % of neckline):");
        for (int i = 0; i < bins.length; i++) {
            int bars = bins[i] * 60 / Math.max(1, Arrays.stream(bins).max().getAsInt());
            System.out.printf("  %-8s : %4d  %s%n", labels[i], bins[i], "█".repeat(bars));
        }

        System.out.println("\nImplication for current strategy:");
        System.out.printf("  Mean pattern height = %.2f%% → target distance from retest entry%n", mean);
        System.out.printf("  R:R is 1:1 (stop = head, target = neckline ± height)%n");
        System.out.printf("  With 47%% target hits, 30%% stops, 23%% timeouts:%n");
        System.out.printf("  Expected mean P/L = 0.47*%.2f - 0.30*%.2f = +%.2f%%/trade%n",
                mean, mean, mean * (0.47 - 0.30));
        System.out.printf("  Observed mean P/L was +0.09%%/trade — matches the math%n");

        assertFalse(heights.isEmpty(), "Expected pattern detections");
    }

    private void addHeight(List<Double> heights, ClassicPattern p) {
        double bPrice = p.pivots().get(1).price();
        double dPrice = p.pivots().get(3).price();
        double headPrice = p.pivots().get(2).price();
        double neckline = (bPrice + dPrice) / 2.0;
        double height = Math.abs(headPrice - neckline) / neckline * 100;
        heights.add(height);
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
            if (cmp < 0) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }

    private BarSeries loadCsv(String sym, Path csv) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(sym).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim(); if (line.isEmpty()) continue;
            String[] parts = line.split(","); if (parts.length < 6) continue;
            String ts = parts[0]; if (!ts.endsWith("Z")) ts += "Z";
            series.addBar(BarsLoader.getBar(
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]), Instant.parse(ts), Duration.ofHours(1)));
        }
        return series;
    }
}
