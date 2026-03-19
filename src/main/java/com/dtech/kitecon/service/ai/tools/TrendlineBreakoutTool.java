package com.dtech.kitecon.service.ai.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TrendlineBreakoutTool - Validates if a trendline is truly broken
 * Uses programmatic analysis of price action relative to trendline projection
 */
@Component
@Slf4j
public class TrendlineBreakoutTool implements AITool {

    private static final double BREAKOUT_THRESHOLD_PERCENT = 0.5; // 0.5% above/below trendline
    private static final double VOLUME_SPIKE_THRESHOLD = 1.3; // 30% above average volume

    @Override
    public String getToolName() {
        return "trendline_breakout_validator";
    }

    @Override
    public String getDescription() {
        return "Validates if a trendline has been genuinely broken by price action. " +
               "Checks breakout strength, volume confirmation, and false breakout patterns.";
    }

    @Override
    public PatternType getSupportedPattern() {
        return PatternType.TRENDLINE_BREAKOUT;
    }

    @Override
    public ObjectNode getInputSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();

        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        // Trendline definition
        ObjectNode trendline = properties.putObject("trendline");
        trendline.put("type", "object");
        trendline.put("description", "Two points defining the trendline");

        // Breakout type
        ObjectNode breakoutType = properties.putObject("breakoutType");
        breakoutType.put("type", "string");
        breakoutType.put("enum", factory.arrayNode().add("above").add("below"));

        // Required fields
        schema.putArray("required").add("trendline").add("breakoutType");

        return schema;
    }

    @Override
    public ValidationResult validate(ValidationInput input) {
        try {
            // Extract trendline from drawings
            ValidationInput.Drawing trendline = findTrendline(input.getDrawings());
            if (trendline == null || trendline.getPoints().size() < 2) {
                return ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.0)
                    .reason("No valid trendline found in chart drawings")
                    .detailedFeedback("Please draw a trendline with at least 2 points")
                    .build();
            }

            // Get the two points
            ValidationInput.Point p1 = trendline.getPoints().get(0);
            ValidationInput.Point p2 = trendline.getPoints().get(1);

            // Calculate trendline equation: y = mx + b
            double slope = (p2.getPrice() - p1.getPrice()) / (double) (p2.getTimestamp() - p1.getTimestamp());
            double intercept = p1.getPrice() - (slope * p1.getTimestamp());

            // Get recent candles
            List<ValidationInput.OHLCData> candles = input.getPriceData();
            if (candles == null || candles.isEmpty()) {
                return ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.0)
                    .reason("No price data available for validation")
                    .build();
            }

            // Get the latest candle
            ValidationInput.OHLCData latestCandle = candles.get(candles.size() - 1);

            // Project trendline to latest candle time
            double projectedPrice = (slope * latestCandle.getTimestamp()) + intercept;

            // Determine breakout direction
            String breakoutDirection = determineBreakoutDirection(latestCandle, projectedPrice);

            if (breakoutDirection.equals("none")) {
                return ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.3)
                    .reason("No breakout detected. Price is still near trendline.")
                    .detailedFeedback(String.format(
                        "Current price: %.2f, Trendline projection: %.2f. " +
                        "Waiting for decisive move above %.2f or below %.2f",
                        latestCandle.getClose(),
                        projectedPrice,
                        projectedPrice * (1 + BREAKOUT_THRESHOLD_PERCENT / 100),
                        projectedPrice * (1 - BREAKOUT_THRESHOLD_PERCENT / 100)
                    ))
                    .build();
            }

            // Calculate breakout strength
            double breakoutPercent = Math.abs((latestCandle.getClose() - projectedPrice) / projectedPrice * 100);

            // Check volume confirmation
            double avgVolume = calculateAverageVolume(candles, 20);
            double volumeRatio = latestCandle.getVolume() / avgVolume;
            boolean volumeConfirmed = volumeRatio >= VOLUME_SPIKE_THRESHOLD;

            // Check for false breakout (retest logic)
            boolean isFalseBreakout = checkFalseBreakout(candles, projectedPrice, slope, breakoutDirection);

            // Calculate confidence
            double confidence = calculateConfidence(breakoutPercent, volumeRatio, !isFalseBreakout);

            // Build result
            ValidationResult result = ValidationResult.builder()
                .isValid(confidence > 0.6)
                .confidence(confidence)
                .build();

            result.addMetric("projected_price", projectedPrice);
            result.addMetric("actual_price", latestCandle.getClose());
            result.addMetric("breakout_percent", breakoutPercent);
            result.addMetric("breakout_direction", breakoutDirection);
            result.addMetric("volume_ratio", volumeRatio);
            result.addMetric("volume_confirmed", volumeConfirmed);
            result.addMetric("is_false_breakout", isFalseBreakout);

            // Build feedback
            if (result.isValid()) {
                result.setReason(String.format(
                    "%s breakout confirmed! Price moved %.2f%% %s trendline",
                    breakoutDirection.equals("above") ? "Bullish" : "Bearish",
                    breakoutPercent,
                    breakoutDirection
                ));

                result.setDetailedFeedback(String.format(
                    "Valid trendline breakout detected:\n" +
                    "• Breakout direction: %s\n" +
                    "• Breakout strength: %.2f%%\n" +
                    "• Volume confirmation: %s (%.1fx average)\n" +
                    "• False breakout risk: %s\n" +
                    "• Confidence: %.1f%%",
                    breakoutDirection.toUpperCase(),
                    breakoutPercent,
                    volumeConfirmed ? "YES" : "NO",
                    volumeRatio,
                    isFalseBreakout ? "HIGH" : "LOW",
                    confidence * 100
                ));

                // Trading implications
                double targetPrice = calculateTargetPrice(projectedPrice, breakoutPercent, breakoutDirection);
                double stopLoss = calculateStopLoss(projectedPrice, breakoutDirection);

                result.setTradingImplication(ValidationResult.TradingImplication.builder()
                    .bias(breakoutDirection.equals("above") ? "bullish" : "bearish")
                    .targetPrice(targetPrice)
                    .stopLoss(stopLoss)
                    .timeframe(input.getTimeframe())
                    .strategy(String.format(
                        "%s on breakout confirmation with target %.2f and stop loss %.2f",
                        breakoutDirection.equals("above") ? "BUY" : "SELL",
                        targetPrice,
                        stopLoss
                    ))
                    .build());
            } else {
                result.setReason("Breakout not confirmed. Insufficient strength or volume.");

                result.setDetailedFeedback(String.format(
                    "Breakout validation failed:\n" +
                    "• Breakout strength: %.2f%% (threshold: %.2f%%)\n" +
                    "• Volume: %.1fx average (threshold: %.1fx)\n" +
                    "• False breakout risk: %s\n" +
                    "• Suggestion: Wait for stronger confirmation",
                    breakoutPercent,
                    BREAKOUT_THRESHOLD_PERCENT,
                    volumeRatio,
                    VOLUME_SPIKE_THRESHOLD,
                    isFalseBreakout ? "HIGH" : "LOW"
                ));

                if (!volumeConfirmed) {
                    result.addSuggestion("Wait for volume confirmation above " + VOLUME_SPIKE_THRESHOLD + "x average");
                }
                if (isFalseBreakout) {
                    result.addSuggestion("Possible false breakout. Wait for retest and continuation");
                }
                if (breakoutPercent < BREAKOUT_THRESHOLD_PERCENT) {
                    result.addSuggestion("Breakout too weak. Wait for decisive move beyond " + BREAKOUT_THRESHOLD_PERCENT + "%");
                }
            }

            return result;

        } catch (Exception e) {
            log.error("Error validating trendline breakout", e);
            return ValidationResult.builder()
                .isValid(false)
                .confidence(0.0)
                .reason("Validation error: " + e.getMessage())
                .build();
        }
    }

    private ValidationInput.Drawing findTrendline(List<ValidationInput.Drawing> drawings) {
        if (drawings == null) return null;

        return drawings.stream()
            .filter(d -> "trendline".equalsIgnoreCase(d.getType()) ||
                        "trend_line".equalsIgnoreCase(d.getType()) ||
                        "line".equalsIgnoreCase(d.getType()))
            .findFirst()
            .orElse(null);
    }

    private String determineBreakoutDirection(ValidationInput.OHLCData candle, double projectedPrice) {
        double threshold = projectedPrice * BREAKOUT_THRESHOLD_PERCENT / 100;

        if (candle.getClose() > projectedPrice + threshold) {
            return "above";
        } else if (candle.getClose() < projectedPrice - threshold) {
            return "below";
        } else {
            return "none";
        }
    }

    private double calculateAverageVolume(List<ValidationInput.OHLCData> candles, int periods) {
        if (candles.size() < periods) {
            periods = candles.size();
        }

        long totalVolume = 0;
        int count = 0;

        for (int i = candles.size() - periods; i < candles.size(); i++) {
            if (candles.get(i).getVolume() != null) {
                totalVolume += candles.get(i).getVolume();
                count++;
            }
        }

        return count > 0 ? (double) totalVolume / count : 0;
    }

    private boolean checkFalseBreakout(List<ValidationInput.OHLCData> candles,
                                      double projectedPrice,
                                      double slope,
                                      String breakoutDirection) {
        // Check last 3 candles for retest pattern
        int candleCount = Math.min(3, candles.size());

        for (int i = candles.size() - candleCount; i < candles.size() - 1; i++) {
            ValidationInput.OHLCData candle = candles.get(i);

            if (breakoutDirection.equals("above")) {
                // If any recent candle closed back below trendline, it's a false breakout
                if (candle.getClose() < projectedPrice) {
                    return true;
                }
            } else {
                // If any recent candle closed back above trendline, it's a false breakout
                if (candle.getClose() > projectedPrice) {
                    return true;
                }
            }
        }

        return false;
    }

    private double calculateConfidence(double breakoutPercent, double volumeRatio, boolean notFalseBreakout) {
        double confidence = 0.0;

        // Breakout strength component (max 40%)
        if (breakoutPercent >= BREAKOUT_THRESHOLD_PERCENT * 2) {
            confidence += 0.4;
        } else if (breakoutPercent >= BREAKOUT_THRESHOLD_PERCENT) {
            confidence += 0.3;
        } else {
            confidence += 0.1;
        }

        // Volume component (max 35%)
        if (volumeRatio >= VOLUME_SPIKE_THRESHOLD * 1.5) {
            confidence += 0.35;
        } else if (volumeRatio >= VOLUME_SPIKE_THRESHOLD) {
            confidence += 0.25;
        } else {
            confidence += 0.1;
        }

        // False breakout check (max 25%)
        if (notFalseBreakout) {
            confidence += 0.25;
        }

        return Math.min(confidence, 1.0);
    }

    private double calculateTargetPrice(double projectedPrice, double breakoutPercent, String direction) {
        // Target is typically 2x the breakout distance
        double distance = projectedPrice * breakoutPercent / 100;
        return direction.equals("above") ?
            projectedPrice + (distance * 2) :
            projectedPrice - (distance * 2);
    }

    private double calculateStopLoss(double projectedPrice, String direction) {
        // Stop loss just below/above the trendline
        double stopDistance = projectedPrice * 1.0 / 100; // 1% margin
        return direction.equals("above") ?
            projectedPrice - stopDistance :
            projectedPrice + stopDistance;
    }
}
