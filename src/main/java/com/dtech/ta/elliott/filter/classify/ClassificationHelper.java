package com.dtech.ta.elliott.filter.classify;

import java.util.List;
import java.util.Set;

public class ClassificationHelper {

    private static final Set<String> COMPRESSION_PATTERNS = Set.of(
            "SYMMETRICAL_TRIANGLE", "ASCENDING_TRIANGLE", "DESCENDING_TRIANGLE",
            "ASCENDING_CHANNEL", "DESCENDING_CHANNEL", "HORIZONTAL_CHANNEL"
    );

    private static final Set<String> BULLISH_COMPRESSION_PATTERNS = Set.of(
            "FALLING_WEDGE", "ASCENDING_TRIANGLE", "BULL_FLAG", "BULL_PENNANT"
    );

    private static final Set<String> BEARISH_COMPRESSION_PATTERNS = Set.of(
            "RISING_WEDGE", "DESCENDING_TRIANGLE", "BEAR_FLAG", "BEAR_PENNANT"
    );

    private static final Set<String> TERMINAL_PATTERNS = Set.of(
            "RISING_WEDGE", "HEAD_AND_SHOULDERS", "DOUBLE_TOP", "TRIPLE_TOP", "BROADENING_TOP"
    );

    private static final Set<String> BULLISH_REVERSAL_SEEDS = Set.of(
            "DOUBLE_BOTTOM", "INVERTED_HEAD_AND_SHOULDERS", "ROUNDING_BOTTOM", "TRIPLE_BOTTOM"
    );

    private static final Set<String> BEARISH_REVERSAL_SEEDS = Set.of(
            "DOUBLE_TOP", "HEAD_AND_SHOULDERS", "TRIPLE_TOP"
    );

    public static boolean containsCompressionPatterns(List<String> patterns) {
        return patterns != null && patterns.stream().anyMatch(COMPRESSION_PATTERNS::contains);
    }

    public static boolean containsBullishCompressionPatterns(List<String> patterns) {
        return patterns != null && patterns.stream().anyMatch(BULLISH_COMPRESSION_PATTERNS::contains);
    }

    public static boolean containsBearishCompressionPatterns(List<String> patterns) {
        return patterns != null && patterns.stream().anyMatch(BEARISH_COMPRESSION_PATTERNS::contains);
    }

    public static boolean containsTerminalPatterns(List<String> patterns) {
        return patterns != null && patterns.stream().anyMatch(TERMINAL_PATTERNS::contains);
    }

    public static boolean containsBullishReversalSeeds(List<String> patterns) {
        return patterns != null && patterns.stream().anyMatch(BULLISH_REVERSAL_SEEDS::contains);
    }

    public static boolean containsBearishReversalSeeds(List<String> patterns) {
        return patterns != null && patterns.stream().anyMatch(BEARISH_REVERSAL_SEEDS::contains);
    }
}
