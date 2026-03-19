package com.dtech.kitecon.service.ai.tools;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ValidationResult - Result of pattern validation
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ValidationResult {

    /**
     * Is the pattern valid?
     */
    @Builder.Default
    private boolean isValid = false;

    /**
     * Confidence score (0.0 to 1.0)
     */
    @Builder.Default
    private double confidence = 0.0;

    /**
     * Human-readable explanation of the validation
     */
    private String reason;

    /**
     * Detailed feedback for the user
     */
    private String detailedFeedback;

    /**
     * Suggestions for improving the pattern drawing
     */
    @Builder.Default
    private List<String> suggestions = new ArrayList<>();

    /**
     * Rule violations (if any)
     */
    @Builder.Default
    private List<RuleViolation> violations = new ArrayList<>();

    /**
     * Calculated metrics related to the pattern
     */
    @Builder.Default
    private Map<String, Object> metrics = new HashMap<>();

    /**
     * Alternative interpretations of the pattern
     */
    @Builder.Default
    private List<AlternativeInterpretation> alternatives = new ArrayList<>();

    /**
     * Trading implications (if pattern is valid)
     */
    private TradingImplication tradingImplication;

    /**
     * RuleViolation - A specific rule that was violated
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RuleViolation {
        private String ruleName;
        private String description;
        private String severity; // error, warning, info
    }

    /**
     * AlternativeInterpretation - Other ways to interpret the pattern
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AlternativeInterpretation {
        private PatternType patternType;
        private double probability;
        private String description;
    }

    /**
     * TradingImplication - What this pattern means for trading
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TradingImplication {
        private String bias; // bullish, bearish, neutral
        private Double targetPrice;
        private Double stopLoss;
        private String timeframe;
        private String strategy;
    }

    /**
     * Helper method to add a suggestion
     */
    public void addSuggestion(String suggestion) {
        if (suggestions == null) {
            suggestions = new ArrayList<>();
        }
        suggestions.add(suggestion);
    }

    /**
     * Helper method to add a violation
     */
    public void addViolation(String ruleName, String description, String severity) {
        if (violations == null) {
            violations = new ArrayList<>();
        }
        violations.add(RuleViolation.builder()
            .ruleName(ruleName)
            .description(description)
            .severity(severity)
            .build());
    }

    /**
     * Helper method to add a metric
     */
    public void addMetric(String key, Object value) {
        if (metrics == null) {
            metrics = new HashMap<>();
        }
        metrics.put(key, value);
    }

    /**
     * Helper method to add an alternative
     */
    public void addAlternative(PatternType type, double probability, String description) {
        if (alternatives == null) {
            alternatives = new ArrayList<>();
        }
        alternatives.add(AlternativeInterpretation.builder()
            .patternType(type)
            .probability(probability)
            .description(description)
            .build());
    }
}
