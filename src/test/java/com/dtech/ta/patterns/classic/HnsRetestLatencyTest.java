package com.dtech.ta.patterns.classic;

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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Does Williams-pivot HNS detection fire BEFORE the post-breakout retest?
 *
 * The trader's question: with a 6-bar pivot-confirmation lag on Williams pivots,
 * by the time the right-shoulder (E) is detected, the neckline has often been
 * broken. The retest of the neckline is the canonical re-entry opportunity.
 * If we can detect the pattern BEFORE that retest, the 6-bar lag is acceptable.
 *
 * Methodology per HNS/Reverse HNS detection:
 *   1. Identify the neckline (mean of B and D pivot prices)
 *   2. Identify the breakout bar = first bar AFTER E where close crosses neckline
 *      (down for HNS, up for REV_HNS)
 *   3. Identify the retest bar = first bar AFTER breakout where price returns
 *      to within RETEST_TOLERANCE_PCT of the neckline
 *   4. Identify the detection-ready bar = max(E_bar + PIVOT_BARS, F_bar) =
 *      earliest bar at which the Williams scanner could see the E pivot AND have
 *      F (current bar) past it
 *   5. Compare: is detection_ready_bar < retest_bar? If yes, we have the
 *      breakout in time to act on the retest.
 *
 * Outputs distribution of (detection-relative-to-retest) lag in bars.
 *
 * @see AllPatternsHourlyScanTest
 */
class HnsRetestLatencyTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final int PIVOT_BARS = 6;
    private static final int LOOKBACK_WINDOW = 200;
    private static final int SLIDE_STEP = 100;
    private static final double RETEST_TOLERANCE_PCT = 0.5;  // 0.5% of neckline
    private static final int MAX_FORWARD_BARS = 50;  // give up looking after 50 bars

    @Test
    void measureHnsDetectionTimingVsRetest() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<Event> events = new ArrayList<>();

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries full = loadCsv(sym, csv);
                if (full.getBarCount() < LOOKBACK_WINDOW + 50) continue;

                for (int we = LOOKBACK_WINDOW; we <= full.getEndIndex(); we += SLIDE_STEP) {
                    BarSeries w = sliceUpTo(full, we);
                    List<PivotPoint> pivots = extractor.extract(w, PIVOT_BARS, PIVOT_BARS, PivotType.BOTH);

                    for (HnsPattern p : new HnsClassicDetector().findAll(w, pivots, LOOKBACK_WINDOW)) {
                        Event e = analyse(sym, "HNS", p, full, false);
                        if (e != null) events.add(e);
                    }
                    for (ReverseHnsPattern p : new ReverseHnsClassicDetector().findAll(w, pivots, LOOKBACK_WINDOW)) {
                        Event e = analyse(sym, "REV_HNS", p, full, true);
                        if (e != null) events.add(e);
                    }
                }
            }
        }

        // Dedup by (symbol, type, eBarIndex)
        var unique = new java.util.LinkedHashMap<String, Event>();
        for (Event e : events) {
            unique.putIfAbsent(e.symbol + "|" + e.type + "|" + e.eBarIndex, e);
        }
        List<Event> dedup = new ArrayList<>(unique.values());

        // Tally
        int total = 0, withBreakout = 0, withRetest = 0;
        int detectedBeforeBreakout = 0;
        int detectedBeforeRetest = 0;
        int detectedAfterRetest = 0;
        int sumDetectionLagFromBreakout = 0;
        int sumRetestLagFromBreakout = 0;
        int sumDetectionLagFromRetest = 0;
        for (Event e : dedup) {
            if (e.breakoutBar < 0) continue;
            total++;
            withBreakout++;
            if (e.detectedReadyBar < e.breakoutBar) detectedBeforeBreakout++;
            sumDetectionLagFromBreakout += e.detectedReadyBar - e.breakoutBar;
            if (e.retestBar < 0) continue;
            withRetest++;
            sumRetestLagFromBreakout += e.retestBar - e.breakoutBar;
            int lagFromRetest = e.detectedReadyBar - e.retestBar;
            sumDetectionLagFromRetest += lagFromRetest;
            if (e.detectedReadyBar <= e.retestBar) detectedBeforeRetest++;
            else detectedAfterRetest++;
        }

        System.out.println("\n========== HNS DETECTION TIMING VS RETEST ==========");
        System.out.printf("Total unique HNS/REV_HNS patterns: %d%n", dedup.size());
        System.out.printf("Patterns where breakout was found:  %d%n", withBreakout);
        System.out.printf("Patterns where retest was found:    %d%n", withRetest);
        System.out.println();

        if (withBreakout > 0) {
            System.out.printf("Mean detection lag from breakout:  %+.1f bars  (negative = detected before breakout)%n",
                    (double) sumDetectionLagFromBreakout / withBreakout);
            System.out.printf("  Detected BEFORE breakout: %d / %d (%.0f%%)%n",
                    detectedBeforeBreakout, withBreakout, 100.0 * detectedBeforeBreakout / withBreakout);
        }
        if (withRetest > 0) {
            System.out.printf("Mean retest delay from breakout:   %.1f bars%n",
                    (double) sumRetestLagFromBreakout / withRetest);
            System.out.printf("Mean detection lag from retest:    %+.1f bars  (negative = detected before retest)%n",
                    (double) sumDetectionLagFromRetest / withRetest);
            System.out.printf("  Detected AT or BEFORE retest: %d / %d (%.0f%%)  <- actionable on retest entry%n",
                    detectedBeforeRetest, withRetest, 100.0 * detectedBeforeRetest / withRetest);
            System.out.printf("  Detected AFTER retest:        %d / %d (%.0f%%)  <- missed the retest entry%n",
                    detectedAfterRetest, withRetest, 100.0 * detectedAfterRetest / withRetest);
        }

        // Distribution of detection-lag-from-retest in bins
        System.out.println("\nDistribution of detection_ready - retest (bars):");
        int[] bins = new int[6];  // < -10, -10..-5, -5..0, 0..5, 5..10, > 10
        for (Event e : dedup) {
            if (e.retestBar < 0) continue;
            int lag = e.detectedReadyBar - e.retestBar;
            if (lag < -10) bins[0]++;
            else if (lag < -5) bins[1]++;
            else if (lag <= 0) bins[2]++;
            else if (lag <= 5) bins[3]++;
            else if (lag <= 10) bins[4]++;
            else bins[5]++;
        }
        String[] labels = {"<-10", "-10..-6", "-5..0", "+1..+5", "+6..+10", ">+10"};
        for (int i = 0; i < bins.length; i++) {
            System.out.printf("  %-9s : %4d%n", labels[i], bins[i]);
        }

        assertFalse(dedup.isEmpty(), "Expected HNS / REV_HNS detections");
    }

    private Event analyse(String sym, String type, ClassicPattern pattern, BarSeries full, boolean bullish) {
        if (pattern.pivots().size() < 5) return null;
        int eBarIndex = pattern.pivots().get(4).barIndex();
        if (eBarIndex >= full.getBarCount()) return null;

        // Neckline = average of B (pivot[1]) and D (pivot[3])
        double bPrice = pattern.pivots().get(1).price();
        double dPrice = pattern.pivots().get(3).price();
        double neckline = (bPrice + dPrice) / 2.0;

        Event e = new Event();
        e.symbol = sym;
        e.type = type;
        e.eBarIndex = eBarIndex;
        e.detectedReadyBar = eBarIndex + PIVOT_BARS;  // Williams: pivot E confirmed +PIVOT_BARS bars later
        e.neckline = neckline;
        e.breakoutBar = -1;
        e.retestBar = -1;

        // Find breakout bar = first bar AFTER eBarIndex where close crosses neckline
        // For HNS (bearish): close < neckline.  For REV_HNS (bullish): close > neckline.
        int maxBreakout = Math.min(eBarIndex + MAX_FORWARD_BARS, full.getEndIndex());
        for (int i = eBarIndex + 1; i <= maxBreakout; i++) {
            double c = full.getBar(i).getClosePrice().doubleValue();
            if ((bullish && c > neckline) || (!bullish && c < neckline)) {
                e.breakoutBar = i;
                break;
            }
        }
        if (e.breakoutBar < 0) return e;

        // Find retest bar = first bar after breakout where price returns within RETEST_TOLERANCE_PCT of neckline
        double tolerance = neckline * RETEST_TOLERANCE_PCT / 100.0;
        int maxRetest = Math.min(e.breakoutBar + MAX_FORWARD_BARS, full.getEndIndex());
        for (int i = e.breakoutBar + 1; i <= maxRetest; i++) {
            Bar bar = full.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double lo = bar.getLowPrice().doubleValue();
            // Did the bar's range touch the neckline within tolerance?
            if (lo <= neckline + tolerance && hi >= neckline - tolerance) {
                e.retestBar = i;
                break;
            }
        }
        return e;
    }

    private BarSeries sliceUpTo(BarSeries source, int endIdx) {
        BarSeries copy = new BaseBarSeriesBuilder().withName(source.getName() + "_to" + endIdx).build();
        int end = Math.min(source.getEndIndex(), endIdx);
        for (int i = source.getBeginIndex(); i <= end; i++) {
            Bar src = source.getBar(i);
            copy.addBar(BarsLoader.getBar(
                    src.getOpenPrice().doubleValue(), src.getHighPrice().doubleValue(),
                    src.getLowPrice().doubleValue(), src.getClosePrice().doubleValue(),
                    src.getVolume().doubleValue(), src.getEndTime(), Duration.ofHours(1)));
        }
        return copy;
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

    private static class Event {
        String symbol; String type;
        int eBarIndex;
        int detectedReadyBar;
        int breakoutBar;
        int retestBar;
        double neckline;
    }
}
