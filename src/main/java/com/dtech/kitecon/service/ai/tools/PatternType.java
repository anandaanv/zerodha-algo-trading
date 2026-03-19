package com.dtech.kitecon.service.ai.tools;

/**
 * PatternType - Enum of supported chart patterns
 */
public enum PatternType {
    TRENDLINE_BREAKOUT("Trendline Breakout"),
    ASCENDING_TRIANGLE("Ascending Triangle"),
    DESCENDING_TRIANGLE("Descending Triangle"),
    SYMMETRICAL_TRIANGLE("Symmetrical Triangle"),
    SUPPORT_RESISTANCE("Support/Resistance Level"),
    ELLIOTT_IMPULSE("Elliott Wave - Impulse"),
    ELLIOTT_CORRECTIVE("Elliott Wave - Corrective"),
    FIBONACCI_RETRACEMENT("Fibonacci Retracement"),
    HEAD_AND_SHOULDERS("Head and Shoulders"),
    INVERSE_HEAD_AND_SHOULDERS("Inverse Head and Shoulders"),
    CHANNEL("Channel (Parallel Lines)"),
    DOUBLE_TOP("Double Top"),
    DOUBLE_BOTTOM("Double Bottom"),
    HARMONIC("Harmonic Pattern"),
    FLAG("Flag Pattern"),
    PENNANT("Pennant Pattern"),
    WEDGE("Wedge Pattern"),
    UNKNOWN("Unknown Pattern");

    private final String displayName;

    PatternType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Identify pattern type from user comment using keyword matching
     */
    public static PatternType fromComment(String comment) {
        if (comment == null) return UNKNOWN;

        String lower = comment.toLowerCase();

        // Trendline breakout
        if (lower.contains("trendline") && (lower.contains("break") || lower.contains("broken"))) {
            return TRENDLINE_BREAKOUT;
        }

        // Triangles
        if (lower.contains("ascending triangle") || lower.contains("asc triangle")) {
            return ASCENDING_TRIANGLE;
        }
        if (lower.contains("descending triangle") || lower.contains("desc triangle")) {
            return DESCENDING_TRIANGLE;
        }
        if (lower.contains("symmetrical triangle") || lower.contains("sym triangle")) {
            return SYMMETRICAL_TRIANGLE;
        }
        if (lower.contains("triangle")) {
            return SYMMETRICAL_TRIANGLE; // Default triangle type
        }

        // Elliott Wave
        if (lower.contains("elliott") || lower.contains("wave")) {
            if (lower.contains("impulse") || lower.contains("12345") || lower.matches(".*wave\\s*[12345].*")) {
                return ELLIOTT_IMPULSE;
            }
            if (lower.contains("corrective") || lower.contains("abc") || lower.contains("wxy")) {
                return ELLIOTT_CORRECTIVE;
            }
            return ELLIOTT_IMPULSE; // Default to impulse
        }

        // Support/Resistance
        if (lower.contains("support") || lower.contains("resistance") ||
            lower.contains("s/r") || lower.contains("sr level")) {
            return SUPPORT_RESISTANCE;
        }

        // Fibonacci
        if (lower.contains("fib") || lower.contains("fibonacci")) {
            return FIBONACCI_RETRACEMENT;
        }

        // Head and Shoulders
        if (lower.contains("head") && lower.contains("shoulders")) {
            if (lower.contains("inverse") || lower.contains("inverted")) {
                return INVERSE_HEAD_AND_SHOULDERS;
            }
            return HEAD_AND_SHOULDERS;
        }

        // Channel
        if (lower.contains("channel") || lower.contains("parallel")) {
            return CHANNEL;
        }

        // Double patterns
        if (lower.contains("double top")) return DOUBLE_TOP;
        if (lower.contains("double bottom")) return DOUBLE_BOTTOM;

        // Flags and Pennants
        if (lower.contains("flag")) return FLAG;
        if (lower.contains("pennant")) return PENNANT;
        if (lower.contains("wedge")) return WEDGE;

        // Harmonic
        if (lower.contains("gartley") || lower.contains("butterfly") ||
            lower.contains("bat") || lower.contains("crab") || lower.contains("harmonic")) {
            return HARMONIC;
        }

        return UNKNOWN;
    }
}
