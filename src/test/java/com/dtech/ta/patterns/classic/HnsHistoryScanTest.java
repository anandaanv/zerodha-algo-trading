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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * History scan: run HNS and Reverse HNS detectors across years of daily data
 * for major Indian stocks. For each detection, check whether the predicted
 * bearish/bullish target (neckline +/- pattern height) was hit within 30 bars.
 *
 * This serves as empirical validation in lieu of curated external golden examples.
 * Detections that hit their targets are real H&S occurrences whose validity is
 * confirmed by subsequent price action.
 *
 * CSV data lives at /tmp/hns-scan-bars/{SYMBOL}.csv; regenerate via the Python
 * dump script (see PR description). Test skips if data is missing.
 *
 * @see docs/spec-classic-patterns-port.md
 */
class HnsHistoryScanTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hns-scan-bars");
    private static final int PIVOT_BARS = 3;
    private static final int POST_MOVE_LOOKAHEAD = 30;

    @Test
    void scanForValidatedHnsPatterns() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR),
                "Bulk scan data missing at /tmp/hns-scan-bars/; regenerate from local DB");

        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        HnsClassicDetector hnsDet = new HnsClassicDetector();
        ReverseHnsClassicDetector revDet = new ReverseHnsClassicDetector();

        List<Detection> hnsHits = new ArrayList<>();
        List<Detection> revHits = new ArrayList<>();

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < 100) continue;

                List<PivotPoint> pivots = extractor.extract(series, PIVOT_BARS, PIVOT_BARS, PivotType.BOTH);

                for (HnsPattern p : hnsDet.findAll(series, pivots, series.getBarCount())) {
                    Detection d = analyse(sym, "HNS", p, series, false);
                    if (d != null) hnsHits.add(d);
                }
                for (ReverseHnsPattern p : revDet.findAll(series, pivots, series.getBarCount())) {
                    Detection d = analyse(sym, "REV_HNS", p, series, true);
                    if (d != null) revHits.add(d);
                }
            }
        }

        // Rank by target-hit + move magnitude — these are the most validated detections
        hnsHits.sort(Comparator.<Detection>comparingDouble(d -> d.moveMagnitude).reversed());
        revHits.sort(Comparator.<Detection>comparingDouble(d -> d.moveMagnitude).reversed());

        long hnsTargetHits = hnsHits.stream().filter(d -> d.targetHit).count();
        long revTargetHits = revHits.stream().filter(d -> d.targetHit).count();

        System.out.println("\n========== H&S HISTORY SCAN ==========");
        System.out.printf("Stocks scanned: %d daily-bar files%n", listCsvCount());
        System.out.println();
        System.out.printf("HNS detections: %d total, %d hit predicted target (%.0f%%)%n",
                hnsHits.size(), hnsTargetHits, hnsHits.isEmpty() ? 0 : 100.0 * hnsTargetHits / hnsHits.size());
        System.out.printf("REV_HNS detections: %d total, %d hit predicted target (%.0f%%)%n",
                revHits.size(), revTargetHits, revHits.isEmpty() ? 0 : 100.0 * revTargetHits / revHits.size());

        System.out.println("\n--- TOP 10 VALIDATED HNS (largest bearish move within 30 days) ---");
        System.out.printf("  %-12s %-12s %8s %8s %8s %8s%n",
                "symbol", "end_date", "head", "neck", "30d_move", "target?");
        for (int i = 0; i < Math.min(10, hnsHits.size()); i++) {
            Detection d = hnsHits.get(i);
            System.out.printf("  %-12s %-12s %8.2f %8.2f %+7.1f%% %8s%n",
                    d.symbol, d.endDate, d.headPrice, d.neckline, d.move30d,
                    d.targetHit ? "HIT" : "no");
        }

        System.out.println("\n--- TOP 10 VALIDATED REV_HNS (largest bullish move within 30 days) ---");
        System.out.printf("  %-12s %-12s %8s %8s %8s %8s%n",
                "symbol", "end_date", "head", "neck", "30d_move", "target?");
        for (int i = 0; i < Math.min(10, revHits.size()); i++) {
            Detection d = revHits.get(i);
            System.out.printf("  %-12s %-12s %8.2f %8.2f %+7.1f%% %8s%n",
                    d.symbol, d.endDate, d.headPrice, d.neckline, d.move30d,
                    d.targetHit ? "HIT" : "no");
        }

        // Assertions: detector must find something, and a meaningful fraction must validate
        assertFalse(hnsHits.isEmpty() && revHits.isEmpty(),
                "Expected at least some H&S detections across 46 stocks in 5 years of daily data");

        int totalHits = hnsHits.size() + revHits.size();
        long totalTargetHits = hnsTargetHits + revTargetHits;
        double validationRate = (double) totalTargetHits / totalHits;
        System.out.printf("%nOverall validation rate: %.0f%% (%d/%d patterns hit their predicted target)%n",
                validationRate * 100, totalTargetHits, totalHits);

        assertTrue(validationRate >= 0.20,
                String.format("Expected at least 20%% of detected H&S patterns to hit their target. "
                        + "Got %.0f%% (%d/%d).", validationRate * 100, totalTargetHits, totalHits));
    }

    private Detection analyse(String sym, String type, ClassicPattern pattern, BarSeries series, boolean bullish) {
        int endBar = pattern.endBarIndex();
        if (endBar + 5 >= series.getBarCount()) return null;

        double headPrice = pattern.pivots().get(2).price();
        double neckLeft = pattern.pivots().get(1).price();
        double neckRight = pattern.pivots().get(3).price();
        double neckline = (neckLeft + neckRight) / 2.0;
        double height = Math.abs(headPrice - neckline);
        double target = bullish ? neckline + height : neckline - height;
        double startClose = series.getBar(endBar).getClosePrice().doubleValue();

        boolean targetHit = false;
        double maxMagnitude = 0;
        int lookahead = Math.min(POST_MOVE_LOOKAHEAD, series.getBarCount() - endBar - 1);
        for (int off = 1; off <= lookahead; off++) {
            double c = series.getBar(endBar + off).getClosePrice().doubleValue();
            double move = bullish ? (c - startClose) / startClose : (startClose - c) / startClose;
            maxMagnitude = Math.max(maxMagnitude, move);
            if (bullish ? c >= target : c <= target) {
                targetHit = true;
            }
        }
        int idx30 = Math.min(endBar + 30, series.getEndIndex());
        double close30 = series.getBar(idx30).getClosePrice().doubleValue();
        double move30 = (close30 - startClose) / startClose * 100;

        Detection d = new Detection();
        d.symbol = sym;
        d.type = type;
        d.endBar = endBar;
        d.endDate = series.getBar(endBar).getEndTime()
                .atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        d.headPrice = headPrice;
        d.neckline = neckline;
        d.target = target;
        d.move30d = move30;
        d.targetHit = targetHit;
        d.moveMagnitude = Math.abs(maxMagnitude);
        return d;
    }

    private int listCsvCount() {
        try (var s = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            int n = 0;
            for (var ignored : s) n++;
            return n;
        } catch (IOException e) {
            return 0;
        }
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
            Instant time = Instant.parse(ts);
            series.addBar(BarsLoader.getBar(
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]),
                    time,
                    Duration.ofDays(1)));
        }
        return series;
    }

    static class Detection {
        String symbol;
        String type;
        int endBar;
        LocalDate endDate;
        double headPrice;
        double neckline;
        double target;
        double move30d;
        boolean targetHit;
        double moveMagnitude;
    }
}
