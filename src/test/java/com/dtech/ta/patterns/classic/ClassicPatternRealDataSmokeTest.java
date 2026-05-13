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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-data smoke test for ClassicPatternScanner.
 * Validates scanner runs end-to-end on real NSE stock data (CSV dumps from local DB)
 * and produces sensible detection counts across pattern types.
 */
class ClassicPatternRealDataSmokeTest {

    private static final Path DATA_DIR = Paths.get("/tmp/smoke-bars");
    private static final List<String> SYMBOLS = List.of(
            "RELIANCE", "HDFCBANK", "INFY", "TCS",
            "SBIN", "HINDUNILVR", "MARUTI", "BAJFINANCE");
    private static final int LOOKBACK_BARS = 300;

    @Test
    void scannerProducesDetectionsAcrossRealStocks() throws IOException {
        Assumptions.assumeTrue(
                Files.exists(DATA_DIR) && Files.isDirectory(DATA_DIR),
                "Smoke-test data missing at /tmp/smoke-bars/; " +
                        "regenerate CSV dumps from local DB with symbol,timestamp,open,high,low,close,volume format"
        );

        Map<String, ScanResult> results = new LinkedHashMap<>();
        Set<PatternType> distinctPatternTypes = new HashSet<>();
        int totalPatterns = 0;

        // Scan each stock
        for (String symbol : SYMBOLS) {
            Path csvFile = DATA_DIR.resolve(symbol + ".csv");
            Assumptions.assumeTrue(
                    Files.exists(csvFile),
                    "CSV missing for " + symbol + " at " + csvFile
            );

            try {
                BarSeries series = loadFromCsv(symbol, csvFile);
                assertNotNull(series, "Failed to load series for " + symbol);
                assertTrue(series.getBarCount() > 0, "Series has no bars for " + symbol);

                // Run scanner
                ClassicPatternScanner scanner = new ClassicPatternScanner(
                        series,
                        new ClassicPivotExtractor()
                );
                List<ClassicPattern> patterns = scanner.scanAll(LOOKBACK_BARS);
                assertNotNull(patterns, "Scanner returned null for " + symbol);

                // Collect metrics
                Map<PatternType, Integer> typeCounts = new HashMap<>();
                for (ClassicPattern pattern : patterns) {
                    PatternType type = getPatternType(pattern);
                    typeCounts.merge(type, 1, Integer::sum);
                    distinctPatternTypes.add(type);
                }

                ScanResult result = new ScanResult(series.getBarCount(), patterns.size(), typeCounts);
                results.put(symbol, result);
                totalPatterns += patterns.size();

            } catch (Exception e) {
                fail("Scanner crashed for " + symbol + ": " + e.getMessage(), e);
            }
        }

        // Print formatted results table
        printResultsTable(results);

        // Assertions
        assertGreaterThanOrEqual(totalPatterns, 5,
                "Sanity floor: expected at least 5 patterns total across all stocks; got " + totalPatterns);

        assertGreaterThanOrEqual(distinctPatternTypes.size(), 3,
                "Coverage: expected at least 3 distinct pattern types; got " + distinctPatternTypes.size() +
                        " (" + distinctPatternTypes + ")");
    }

    private BarSeries loadFromCsv(String symbol, Path csvFile) throws IOException {
        List<String> lines = Files.readAllLines(csvFile);
        Assumptions.assumeTrue(lines.size() > 1, "CSV has no data rows for " + symbol);

        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();

        // Skip header line
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(",");
            if (parts.length < 6) continue; // Skip malformed rows

            try {
                String timestampStr = parts[0];
                // Ensure ISO-8601 format with Z suffix
                if (!timestampStr.endsWith("Z")) {
                    timestampStr += "Z";
                }
                Instant timestamp = Instant.parse(timestampStr);
                double open = Double.parseDouble(parts[1]);
                double high = Double.parseDouble(parts[2]);
                double low = Double.parseDouble(parts[3]);
                double close = Double.parseDouble(parts[4]);
                double volume = Double.parseDouble(parts[5]);

                Bar bar = BarsLoader.getBar(open, high, low, close, volume, timestamp, Duration.ofHours(1));
                series.addBar(bar);
            } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
                // Skip rows with parse errors
            }
        }

        return series;
    }

    private void printResultsTable(Map<String, ScanResult> results) {
        System.out.println("\n========== CLASSIC PATTERN SCANNER - REAL DATA SMOKE TEST ==========");
        System.out.println(String.format(
                "%-15s %8s %10s %8s %s",
                "Symbol", "Bars", "Patterns", "Types", "Per-type counts"
        ));
        System.out.println("-".repeat(80));

        for (Map.Entry<String, ScanResult> entry : results.entrySet()) {
            String symbol = entry.getKey();
            ScanResult result = entry.getValue();

            String typeCountsStr = formatTypeCounts(result.typeCounts);

            System.out.println(String.format(
                    "%-15s %8d %10d %8d %s",
                    symbol,
                    result.barCount,
                    result.patternCount,
                    result.typeCounts.size(),
                    typeCountsStr
            ));
        }

        System.out.println("-".repeat(80));
        System.out.println("Test completed successfully.\n");
    }

    private String formatTypeCounts(Map<PatternType, Integer> typeCounts) {
        if (typeCounts.isEmpty()) {
            return "(no patterns)";
        }
        StringBuilder sb = new StringBuilder();
        typeCounts.forEach((type, count) -> {
            if (sb.length() > 0) sb.append(" ");
            sb.append(type.name()).append(":").append(count);
        });
        return sb.toString();
    }

    private static class ScanResult {
        final int barCount;
        final int patternCount;
        final Map<PatternType, Integer> typeCounts;

        ScanResult(int barCount, int patternCount, Map<PatternType, Integer> typeCounts) {
            this.barCount = barCount;
            this.patternCount = patternCount;
            this.typeCounts = typeCounts;
        }
    }

    private static void assertGreaterThanOrEqual(int actual, int expected, String message) {
        assertTrue(actual >= expected, message + " (actual: " + actual + ", expected: >= " + expected + ")");
    }

    private PatternType getPatternType(ClassicPattern pattern) {
        if (pattern instanceof TrianglePattern) {
            return PatternType.TRIANGLE;
        } else if (pattern instanceof HnsPattern) {
            return PatternType.HNS;
        } else if (pattern instanceof ReverseHnsPattern) {
            return PatternType.REVERSE_HNS;
        } else if (pattern instanceof DoubleTopPattern) {
            return PatternType.DOUBLE_TOP;
        } else if (pattern instanceof DoubleBottomPattern) {
            return PatternType.DOUBLE_BOTTOM;
        } else if (pattern instanceof BullishVcpPattern) {
            return PatternType.BULLISH_VCP;
        } else if (pattern instanceof BearishVcpPattern) {
            return PatternType.BEARISH_VCP;
        } else if (pattern instanceof BullishFlagPattern) {
            return PatternType.BULLISH_FLAG;
        } else if (pattern instanceof BearishFlagPattern) {
            return PatternType.BEARISH_FLAG;
        } else if (pattern instanceof TrendlinePattern) {
            return pattern.direction() == Direction.BULLISH ? PatternType.UPTREND_LINE : PatternType.DOWNTREND_LINE;
        } else if (pattern instanceof AbcdPattern) {
            return pattern.direction() == Direction.BULLISH ? PatternType.BULLISH_ABCD : PatternType.BEARISH_ABCD;
        } else if (pattern instanceof BatPattern) {
            return pattern.direction() == Direction.BULLISH ? PatternType.BULLISH_BAT : PatternType.BEARISH_BAT;
        } else if (pattern instanceof GartleyPattern) {
            return pattern.direction() == Direction.BULLISH ? PatternType.BULLISH_GARTLEY : PatternType.BEARISH_GARTLEY;
        } else if (pattern instanceof CrabPattern) {
            return pattern.direction() == Direction.BULLISH ? PatternType.BULLISH_CRAB : PatternType.BEARISH_CRAB;
        } else if (pattern instanceof ButterflyPattern) {
            return pattern.direction() == Direction.BULLISH ? PatternType.BULLISH_BUTTERFLY : PatternType.BEARISH_BUTTERFLY;
        } else {
            throw new IllegalArgumentException("Unknown pattern type: " + pattern.getClass().getName());
        }
    }
}
