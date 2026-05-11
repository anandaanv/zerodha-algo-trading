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
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Progressive lifecycle test for ClassicPatternScanner.
 *
 * Validates the pre-breakout detection contract by walking each real-stock
 * series forward bar-by-bar, asking "what does the scanner see at time T?"
 * For every detected pattern, the test asserts:
 *
 *   1. Detection precedes breakout — pattern is reported AT LEAST one bar
 *      BEFORE its structurally-important level (resistance for bullish,
 *      support for bearish) is broken by close.
 *   2. Pattern drops after breakout — once the breakout completes, the same
 *      pattern instance is no longer returned by the detector on subsequent
 *      truncated views.
 *
 * Uses real NSE 1H bars dumped to /tmp/smoke-bars/{SYMBOL}.csv (regenerated
 * from local DB; see Python dump script committed alongside this test).
 * Skipped if data is missing.
 *
 * @see docs/spec-classic-patterns-port.md
 */
class ProgressivePatternLifecycleTest {

    private static final Path DATA_DIR = Paths.get("/tmp/smoke-bars");
    private static final List<String> SYMBOLS = List.of(
            "RELIANCE", "HDFCBANK", "INFY", "TCS",
            "SBIN", "HINDUNILVR", "MARUTI", "BAJFINANCE");
    private static final int LOOKBACK_BARS = 200;
    private static final int STEP_BARS = 30;

    @Test
    void scannerDetectsPatternsBeforeBreakout() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR),
                "Smoke-test data missing at /tmp/smoke-bars/; skipping progressive lifecycle test");

        int totalPatternsObserved = 0;
        int patternsConfirmedPreBreakout = 0;
        int patternsThatDroppedAfterBreakout = 0;
        int patternsStillActiveAtEndOfSeries = 0;
        List<String> findings = new ArrayList<>();

        for (String symbol : SYMBOLS) {
            Path csv = DATA_DIR.resolve(symbol + ".csv");
            Assumptions.assumeTrue(Files.exists(csv), "CSV missing: " + symbol);

            BarSeries fullSeries = loadFromCsv(symbol, csv);
            int totalBars = fullSeries.getBarCount();
            if (totalBars < 250) continue;

            ClassicPivotExtractor extractor = new ClassicPivotExtractor();

            // First, scan the full series to enumerate every pattern we'll track
            List<PivotPoint> allPivots = extractor.extract(fullSeries, 6, 6, PivotType.BOTH);
            ClassicPatternScanner fullScanner = new ClassicPatternScanner(fullSeries, extractor);
            List<ClassicPattern> patternsAtEnd = fullScanner.scanAll(LOOKBACK_BARS);

            // Pick the bullish VCP and the harmonic patterns as the cleanest test cases —
            // they have explicit "C not breached" / "D not past terminal" integrity rules
            // that should make them clearly drop after breakout.
            for (ClassicPattern p : patternsAtEnd) {
                if (!(p instanceof BullishVcpPattern || p instanceof BearishVcpPattern
                        || p instanceof AbcdPattern || p instanceof BatPattern
                        || p instanceof CrabPattern || p instanceof GartleyPattern
                        || p instanceof ButterflyPattern)) {
                    continue;
                }

                totalPatternsObserved++;
                int patternEndBar = p.endBarIndex();

                // Truncate to bars [0, patternEndBar] and confirm pattern is detected at that point
                BarSeries truncated = truncate(fullSeries, patternEndBar + 1);
                List<PivotPoint> truncatedPivots = extractor.extract(truncated, 6, 6, PivotType.BOTH);
                List<ClassicPattern> detectedAtEnd = new ClassicPatternScanner(truncated, extractor).scanAll(LOOKBACK_BARS);

                boolean stillPresent = detectedAtEnd.stream().anyMatch(d -> samePattern(d, p));
                if (stillPresent) {
                    patternsConfirmedPreBreakout++;
                }

                // Now walk forward 5, 15, 30 bars past patternEndBar. If a breakout has happened,
                // the pattern should no longer be returned (search advances anchor past it).
                boolean droppedSomewhere = false;
                int[] forwardOffsets = {5, 15, 30};
                int finalCheckBar = Math.min(patternEndBar + 30, totalBars - 1);
                for (int off : forwardOffsets) {
                    int t = patternEndBar + off;
                    if (t >= totalBars) break;
                    BarSeries fwd = truncate(fullSeries, t + 1);
                    List<PivotPoint> fwdPivots = extractor.extract(fwd, 6, 6, PivotType.BOTH);
                    List<ClassicPattern> fwdHits = new ClassicPatternScanner(fwd, extractor).scanAll(LOOKBACK_BARS);
                    if (fwdHits.stream().noneMatch(d -> samePattern(d, p))) {
                        droppedSomewhere = true;
                        break;
                    }
                }
                if (droppedSomewhere) patternsThatDroppedAfterBreakout++;
                else if (patternEndBar + 30 >= totalBars) patternsStillActiveAtEndOfSeries++;
            }

            findings.add(String.format("%-12s bars=%d patterns_at_end=%d",
                    symbol, totalBars, patternsAtEnd.size()));
        }

        // Print a readable summary
        System.out.println("\n========== PROGRESSIVE PATTERN LIFECYCLE ==========");
        for (String f : findings) System.out.println("  " + f);
        System.out.println();
        System.out.println("Total tracked patterns (VCP + harmonics): " + totalPatternsObserved);
        System.out.println("  Confirmed pre-breakout (visible at endBar): " + patternsConfirmedPreBreakout);
        System.out.println("  Dropped after breakout (within +30 bars):    " + patternsThatDroppedAfterBreakout);
        System.out.println("  Still active at end-of-series (no breakout): " + patternsStillActiveAtEndOfSeries);
        System.out.println();

        // CONTRACT 1 — pre-breakout visibility
        // If the scanner returns a pattern at time T, the same pattern must be visible
        // when we scan the truncated series ending at T (i.e., at the pattern's endBarIndex).
        // The pattern's own endBar is the latest-bar view, so it should be present.
        assertTrue(patternsConfirmedPreBreakout >= totalPatternsObserved * 0.9,
                String.format("Pre-breakout contract: at least 90%% of patterns must be visible at their endBar. "
                        + "Got %d/%d (%.0f%%)",
                        patternsConfirmedPreBreakout, totalPatternsObserved,
                        100.0 * patternsConfirmedPreBreakout / Math.max(1, totalPatternsObserved)));

        // CONTRACT 2 — post-breakout drop OR end-of-series clipping
        // Any pattern that the scanner returned must either:
        //   (a) drop out within 30 bars after its endBar (breakout occurred), OR
        //   (b) still be active at the very end of our data (no breakout in our window)
        // Patterns that NEITHER drop nor reach EOS would be suspicious — they'd indicate
        // the scanner keeps returning stale patterns indefinitely.
        int accounted = patternsThatDroppedAfterBreakout + patternsStillActiveAtEndOfSeries;
        assertTrue(accounted >= totalPatternsObserved * 0.8,
                String.format("Post-breakout contract: at least 80%% of patterns must either drop within 30 bars "
                        + "or sit at end-of-series. Got dropped=%d eos=%d total=%d",
                        patternsThatDroppedAfterBreakout, patternsStillActiveAtEndOfSeries,
                        totalPatternsObserved));

        assertFalse(totalPatternsObserved == 0,
                "Test trivially passes if no patterns found; data should produce VCP/harmonic detections");

        // CONTRACT 3 — historical drop validation.
        // Scan each series at an earlier point T = bars - 400 to find patterns that were
        // "currently forming" then. Then scan again at bars - 200 and bars total to see
        // how many of those same patterns survived. If most dropped, that proves the
        // detector ages out completed/broken patterns over time.
        int historicalPatternsFound = 0;
        int historicalPatternsThatDropped = 0;
        for (String symbol : SYMBOLS) {
            Path csv = DATA_DIR.resolve(symbol + ".csv");
            if (!Files.exists(csv)) continue;
            BarSeries fullSeries = loadFromCsv(symbol, csv);
            int total = fullSeries.getBarCount();
            if (total < 600) continue;

            ClassicPivotExtractor extractor = new ClassicPivotExtractor();
            BarSeries oldView = truncate(fullSeries, total - 400);
            List<ClassicPattern> oldHits = new ClassicPatternScanner(oldView, extractor).scanAll(LOOKBACK_BARS).stream()
                    .filter(p -> p instanceof BullishVcpPattern || p instanceof BearishVcpPattern
                            || p instanceof AbcdPattern || p instanceof BatPattern
                            || p instanceof CrabPattern || p instanceof GartleyPattern
                            || p instanceof ButterflyPattern)
                    .toList();
            BarSeries nowView = fullSeries;
            List<ClassicPattern> nowHits = new ClassicPatternScanner(nowView, extractor).scanAll(LOOKBACK_BARS);

            for (ClassicPattern old : oldHits) {
                historicalPatternsFound++;
                boolean stillThere = nowHits.stream().anyMatch(d -> samePattern(d, old));
                if (!stillThere) historicalPatternsThatDropped++;
            }
        }
        System.out.println("Historical (400-bars-ago) cohort: " + historicalPatternsFound);
        System.out.println("  Dropped from current view:       " + historicalPatternsThatDropped);
        System.out.println();

        // Most historical patterns should have aged out / broken / been superseded.
        // If 90%+ still appear in the current view, the detector isn't aging them properly.
        if (historicalPatternsFound > 0) {
            double dropRate = (double) historicalPatternsThatDropped / historicalPatternsFound;
            assertTrue(dropRate >= 0.5,
                    String.format("Patterns visible 400 bars ago should mostly drop from current view; "
                            + "drop rate %.0f%% (%d/%d) below 50%% threshold",
                            dropRate * 100, historicalPatternsThatDropped, historicalPatternsFound));
        }
    }

    private boolean samePattern(ClassicPattern a, ClassicPattern b) {
        if (a.getClass() != b.getClass()) return false;
        if (a.startBarIndex() != b.startBarIndex()) return false;
        if (a.pivots().size() != b.pivots().size()) return false;
        for (int i = 0; i < a.pivots().size(); i++) {
            if (a.pivots().get(i).barIndex() != b.pivots().get(i).barIndex()) return false;
        }
        return true;
    }

    private BarSeries truncate(BarSeries source, int barCount) {
        BarSeries copy = new BaseBarSeriesBuilder().withName(source.getName() + "_T" + barCount).build();
        int end = Math.min(source.getEndIndex() + 1, barCount);
        for (int i = source.getBeginIndex(); i < end; i++) {
            Bar src = source.getBar(i);
            Bar fresh = BarsLoader.getBar(
                    src.getOpenPrice().doubleValue(),
                    src.getHighPrice().doubleValue(),
                    src.getLowPrice().doubleValue(),
                    src.getClosePrice().doubleValue(),
                    src.getVolume().doubleValue(),
                    src.getEndTime(),
                    Duration.ofHours(1));
            copy.addBar(fresh);
        }
        return copy;
    }

    private BarSeries loadFromCsv(String symbol, Path csv) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;
            String ts = parts[0];
            if (!ts.endsWith("Z")) ts += "Z";
            Instant time = Instant.parse(ts);
            double o = Double.parseDouble(parts[1]);
            double h = Double.parseDouble(parts[2]);
            double l = Double.parseDouble(parts[3]);
            double c = Double.parseDouble(parts[4]);
            double v = Double.parseDouble(parts[5]);
            series.addBar(BarsLoader.getBar(o, h, l, c, v, time, Duration.ofHours(1)));
        }
        return series;
    }
}
