package com.dtech.kitecon.service.ai.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * TriangleValidationTool - Validates triangle patterns
 * Supports: Ascending, Descending, and Symmetrical triangles
 */
@Component
@Slf4j
public class TriangleValidationTool implements AITool {

    private static final int MIN_TOUCHES_PER_LINE = 2;
    private static final int MIN_DURATION_DAYS = 21; // 3 weeks
    private static final double CONVERGENCE_ANGLE_MIN = 10.0; // degrees
    private static final double CONVERGENCE_ANGLE_MAX = 70.0; // degrees
    private static final double TOUCH_TOLERANCE_PERCENT = 1.0; // 1% tolerance for touch detection

    @Override
    public String getToolName() {
        return "triangle_pattern_validator";
    }

    @Override
    public String getDescription() {
        return "Validates triangle patterns (ascending, descending, symmetrical). " +
               "Checks for proper convergence, touch points, volume pattern, and duration.";
    }

    @Override
    public PatternType getSupportedPattern() {
        return PatternType.SYMMETRICAL_TRIANGLE; // Can handle all triangle types
    }

    @Override
    public ObjectNode getInputSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();

        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        // Upper trendline
        ObjectNode upperLine = properties.putObject("upperTrendline");
        upperLine.put("type", "array");
        upperLine.put("description", "Points defining upper resistance line");

        // Lower trendline
        ObjectNode lowerLine = properties.putObject("lowerTrendline");
        lowerLine.put("type", "array");
        lowerLine.put("description", "Points defining lower support line");

        // Pattern type
        ObjectNode patternType = properties.putObject("patternType");
        patternType.put("type", "string");
        patternType.put("enum", factory.arrayNode()
            .add("ascending").add("descending").add("symmetrical"));

        schema.putArray("required").add("upperTrendline").add("lowerTrendline");

        return schema;
    }

    @Override
    public ValidationResult validate(ValidationInput input) {
        try {
            // Extract triangle lines from drawings
            List<ValidationInput.Drawing> trendlines = findTrendlines(input.getDrawings());

            if (trendlines.size() < 2) {
                return ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.0)
                    .reason("Triangle requires two trendlines (upper and lower)")
                    .detailedFeedback("Please draw both upper resistance and lower support lines")
                    .build();
            }

            ValidationInput.Drawing upperLine = trendlines.get(0);
            ValidationInput.Drawing lowerLine = trendlines.get(1);

            // Validate basic structure
            if (upperLine.getPoints().size() < 2 || lowerLine.getPoints().size() < 2) {
                return ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.0)
                    .reason("Each trendline must have at least 2 points")
                    .build();
            }

            List<ValidationInput.OHLCData> priceData = input.getPriceData();
            if (priceData == null || priceData.isEmpty()) {
                return ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.0)
                    .reason("No price data available for validation")
                    .build();
            }

            // Calculate trendline equations
            TrendlineEquation upper = calculateTrendline(upperLine.getPoints());
            TrendlineEquation lower = calculateTrendline(lowerLine.getPoints());

            // Check convergence
            boolean converging = checkConvergence(upper, lower);
            if (!converging) {
                return ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.2)
                    .reason("Lines are parallel or diverging, not forming a triangle")
                    .detailedFeedback("Triangle patterns require converging trendlines. " +
                        "Adjust your lines so they meet at an apex.")
                    .build();
            }

            // Calculate convergence angle
            double angle = calculateConvergenceAngle(upper, lower);
            if (angle < CONVERGENCE_ANGLE_MIN || angle > CONVERGENCE_ANGLE_MAX) {
                return ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.3)
                    .reason(String.format("Convergence angle %.1f° is outside valid range (%.1f° - %.1f°)",
                        angle, CONVERGENCE_ANGLE_MIN, CONVERGENCE_ANGLE_MAX))
                    .detailedFeedback("Triangle is too narrow or too wide. " +
                        "Valid triangles have convergence angles between 10° and 70°.")
                    .build();
            }

            // Count touch points
            int upperTouches = countTouches(priceData, upper, true);
            int lowerTouches = countTouches(priceData, lower, false);

            if (upperTouches < MIN_TOUCHES_PER_LINE || lowerTouches < MIN_TOUCHES_PER_LINE) {
                ValidationResult result = ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.4)
                    .reason(String.format("Insufficient touches: upper=%d, lower=%d (minimum %d each)",
                        upperTouches, lowerTouches, MIN_TOUCHES_PER_LINE))
                    .build();

                result.setDetailedFeedback(String.format(
                    "Triangle needs at least %d touch points on each line.\n" +
                    "Current: %d touches on upper resistance, %d on lower support.\n" +
                    "Add more connecting points or adjust lines to capture more price touches.",
                    MIN_TOUCHES_PER_LINE, upperTouches, lowerTouches
                ));

                if (upperTouches < MIN_TOUCHES_PER_LINE) {
                    result.addSuggestion("Add more touch points to upper resistance line");
                }
                if (lowerTouches < MIN_TOUCHES_PER_LINE) {
                    result.addSuggestion("Add more touch points to lower support line");
                }

                return result;
            }

            // Check duration
            long startTime = Math.min(
                upperLine.getPoints().get(0).getTimestamp(),
                lowerLine.getPoints().get(0).getTimestamp()
            );
            long endTime = priceData.get(priceData.size() - 1).getTimestamp();
            long durationDays = (endTime - startTime) / 86400; // seconds to days

            if (durationDays < MIN_DURATION_DAYS) {
                return ValidationResult.builder()
                    .isValid(false)
                    .confidence(0.5)
                    .reason(String.format("Triangle duration %d days is too short (minimum %d days)",
                        durationDays, MIN_DURATION_DAYS))
                    .detailedFeedback(String.format(
                        "Valid triangles typically take at least 3 weeks to form.\n" +
                        "Current pattern duration: %d days.\n" +
                        "Wait for pattern to develop or check if timeframe is appropriate.",
                        durationDays
                    ))
                    .build();
            }

            // Analyze volume pattern (should be decreasing)
            VolumeAnalysis volumeAnalysis = analyzeVolume(priceData);

            // Determine triangle type
            TriangleType triangleType = determineTriangleType(upper, lower);

            // Calculate apex (intersection point)
            ApexPoint apex = calculateApex(upper, lower);

            // Calculate confidence
            double confidence = calculateConfidence(
                upperTouches,
                lowerTouches,
                durationDays,
                angle,
                volumeAnalysis
            );

            // Build result
            ValidationResult result = ValidationResult.builder()
                .isValid(confidence > 0.65)
                .confidence(confidence)
                .build();

            result.addMetric("triangle_type", triangleType.name());
            result.addMetric("upper_touches", upperTouches);
            result.addMetric("lower_touches", lowerTouches);
            result.addMetric("convergence_angle", angle);
            result.addMetric("duration_days", durationDays);
            result.addMetric("volume_pattern", volumeAnalysis.trend);
            result.addMetric("apex_date", apex.timestamp);
            result.addMetric("apex_price", apex.price);

            if (result.isValid()) {
                result.setReason(String.format(
                    "Valid %s triangle pattern confirmed",
                    triangleType.name().toLowerCase().replace("_", " ")
                ));

                result.setDetailedFeedback(String.format(
                    "Triangle pattern validated:\n" +
                    "• Type: %s\n" +
                    "• Upper touches: %d\n" +
                    "• Lower touches: %d\n" +
                    "• Convergence angle: %.1f°\n" +
                    "• Duration: %d days\n" +
                    "• Volume pattern: %s\n" +
                    "• Apex projected: %s (%.2f)\n" +
                    "• Confidence: %.1f%%",
                    triangleType.getDisplayName(),
                    upperTouches,
                    lowerTouches,
                    angle,
                    durationDays,
                    volumeAnalysis.trend,
                    formatTimestamp(apex.timestamp),
                    apex.price,
                    confidence * 100
                ));

                // Calculate breakout probabilities and targets
                double currentPrice = priceData.get(priceData.size() - 1).getClose();
                double heightAtStart = calculateTriangleHeight(upper, lower, startTime);

                result.setTradingImplication(ValidationResult.TradingImplication.builder()
                    .bias(triangleType == TriangleType.ASCENDING ? "bullish" :
                          triangleType == TriangleType.DESCENDING ? "bearish" : "neutral")
                    .targetPrice(calculateBreakoutTarget(currentPrice, heightAtStart, triangleType))
                    .stopLoss(calculateStopLoss(apex.price, triangleType))
                    .timeframe(input.getTimeframe())
                    .strategy(String.format(
                        "Wait for breakout near apex (%.2f). Expected target: %.2f",
                        apex.price,
                        calculateBreakoutTarget(currentPrice, heightAtStart, triangleType)
                    ))
                    .build());

            } else {
                result.setReason("Triangle pattern incomplete or invalid");
                result.setDetailedFeedback(String.format(
                    "Pattern validation results:\n" +
                    "• Triangle type: %s\n" +
                    "• Upper touches: %d (need %d+)\n" +
                    "• Lower touches: %d (need %d+)\n" +
                    "• Angle: %.1f° (valid: %.1f° - %.1f°)\n" +
                    "• Duration: %d days (need %d+)\n" +
                    "• Volume: %s (ideal: decreasing)\n" +
                    "• Confidence: %.1f%% (need 65%%+)",
                    triangleType.getDisplayName(),
                    upperTouches, MIN_TOUCHES_PER_LINE,
                    lowerTouches, MIN_TOUCHES_PER_LINE,
                    angle, CONVERGENCE_ANGLE_MIN, CONVERGENCE_ANGLE_MAX,
                    durationDays, MIN_DURATION_DAYS,
                    volumeAnalysis.trend,
                    confidence * 100
                ));

                // Provide actionable suggestions
                if (volumeAnalysis.trend.equals("increasing")) {
                    result.addSuggestion("Volume should decrease as triangle matures. " +
                        "Current increasing volume suggests pattern may not be valid.");
                }
                if (upperTouches >= MIN_TOUCHES_PER_LINE && lowerTouches >= MIN_TOUCHES_PER_LINE &&
                    durationDays >= MIN_DURATION_DAYS) {
                    result.addSuggestion("Pattern structure is good. Wait for breakout confirmation.");
                }
            }

            return result;

        } catch (Exception e) {
            log.error("Error validating triangle pattern", e);
            return ValidationResult.builder()
                .isValid(false)
                .confidence(0.0)
                .reason("Validation error: " + e.getMessage())
                .build();
        }
    }

    // Helper classes and methods

    private static class TrendlineEquation {
        double slope;
        double intercept;

        TrendlineEquation(double slope, double intercept) {
            this.slope = slope;
            this.intercept = intercept;
        }

        double getPriceAt(long timestamp) {
            return slope * timestamp + intercept;
        }
    }

    private static class ApexPoint {
        long timestamp;
        double price;

        ApexPoint(long timestamp, double price) {
            this.timestamp = timestamp;
            this.price = price;
        }
    }

    private static class VolumeAnalysis {
        String trend; // increasing, decreasing, stable
        double avgChange;

        VolumeAnalysis(String trend, double avgChange) {
            this.trend = trend;
            this.avgChange = avgChange;
        }
    }

    private enum TriangleType {
        ASCENDING("Ascending Triangle"),
        DESCENDING("Descending Triangle"),
        SYMMETRICAL("Symmetrical Triangle");

        private final String displayName;

        TriangleType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private List<ValidationInput.Drawing> findTrendlines(List<ValidationInput.Drawing> drawings) {
        if (drawings == null) return new ArrayList<>();

        return drawings.stream()
            .filter(d -> "trendline".equalsIgnoreCase(d.getType()) ||
                        "trend_line".equalsIgnoreCase(d.getType()) ||
                        "line".equalsIgnoreCase(d.getType()))
            .limit(2) // Take first two trendlines
            .toList();
    }

    private TrendlineEquation calculateTrendline(List<ValidationInput.Point> points) {
        ValidationInput.Point p1 = points.get(0);
        ValidationInput.Point p2 = points.get(points.size() - 1);

        double slope = (p2.getPrice() - p1.getPrice()) / (double) (p2.getTimestamp() - p1.getTimestamp());
        double intercept = p1.getPrice() - (slope * p1.getTimestamp());

        return new TrendlineEquation(slope, intercept);
    }

    private boolean checkConvergence(TrendlineEquation upper, TrendlineEquation lower) {
        // Lines converge if slopes are different and moving towards each other
        return Math.abs(upper.slope - lower.slope) > 0.000001; // Not parallel
    }

    private double calculateConvergenceAngle(TrendlineEquation upper, TrendlineEquation lower) {
        // Calculate angle between two lines in degrees
        double angle1 = Math.atan(upper.slope);
        double angle2 = Math.atan(lower.slope);
        double angleDiff = Math.abs(angle1 - angle2);
        return Math.toDegrees(angleDiff);
    }

    private int countTouches(List<ValidationInput.OHLCData> priceData,
                            TrendlineEquation line,
                            boolean isUpperLine) {
        int touches = 0;

        for (ValidationInput.OHLCData candle : priceData) {
            double projectedPrice = line.getPriceAt(candle.getTimestamp());
            double tolerance = projectedPrice * TOUCH_TOLERANCE_PERCENT / 100;

            if (isUpperLine) {
                // Check if high touched or crossed upper line
                if (Math.abs(candle.getHigh() - projectedPrice) <= tolerance ||
                    candle.getHigh() >= projectedPrice) {
                    touches++;
                }
            } else {
                // Check if low touched or crossed lower line
                if (Math.abs(candle.getLow() - projectedPrice) <= tolerance ||
                    candle.getLow() <= projectedPrice) {
                    touches++;
                }
            }
        }

        return touches;
    }

    private VolumeAnalysis analyzeVolume(List<ValidationInput.OHLCData> priceData) {
        if (priceData.size() < 2) {
            return new VolumeAnalysis("insufficient_data", 0.0);
        }

        // Calculate average volume change over time
        double totalChange = 0.0;
        int validChanges = 0;

        for (int i = 1; i < priceData.size(); i++) {
            Long prevVolume = priceData.get(i - 1).getVolume();
            Long currVolume = priceData.get(i).getVolume();

            if (prevVolume != null && currVolume != null && prevVolume > 0) {
                double change = (double) (currVolume - prevVolume) / prevVolume;
                totalChange += change;
                validChanges++;
            }
        }

        if (validChanges == 0) {
            return new VolumeAnalysis("no_data", 0.0);
        }

        double avgChange = totalChange / validChanges;

        String trend;
        if (avgChange < -0.02) { // Decreasing by more than 2% on average
            trend = "decreasing";
        } else if (avgChange > 0.02) {
            trend = "increasing";
        } else {
            trend = "stable";
        }

        return new VolumeAnalysis(trend, avgChange);
    }

    private TriangleType determineTriangleType(TrendlineEquation upper, TrendlineEquation lower) {
        double upperSlope = upper.slope;
        double lowerSlope = lower.slope;

        // Ascending: flat top, rising bottom
        if (Math.abs(upperSlope) < 0.000001 && lowerSlope > 0) {
            return TriangleType.ASCENDING;
        }

        // Descending: declining top, flat bottom
        if (upperSlope < 0 && Math.abs(lowerSlope) < 0.000001) {
            return TriangleType.DESCENDING;
        }

        // Symmetrical: both lines converging
        return TriangleType.SYMMETRICAL;
    }

    private ApexPoint calculateApex(TrendlineEquation upper, TrendlineEquation lower) {
        // Solve for intersection: upper.slope * t + upper.intercept = lower.slope * t + lower.intercept
        long timestamp = Math.round((lower.intercept - upper.intercept) / (upper.slope - lower.slope));
        double price = upper.getPriceAt(timestamp);

        return new ApexPoint(timestamp, price);
    }

    private double calculateTriangleHeight(TrendlineEquation upper, TrendlineEquation lower, long timestamp) {
        return Math.abs(upper.getPriceAt(timestamp) - lower.getPriceAt(timestamp));
    }

    private double calculateConfidence(int upperTouches, int lowerTouches, long durationDays,
                                      double angle, VolumeAnalysis volumeAnalysis) {
        double confidence = 0.0;

        // Touch points component (max 35%)
        int totalTouches = upperTouches + lowerTouches;
        if (totalTouches >= 6) confidence += 0.35;
        else if (totalTouches >= 4) confidence += 0.25;
        else confidence += 0.15;

        // Duration component (max 25%)
        if (durationDays >= MIN_DURATION_DAYS * 2) confidence += 0.25;
        else if (durationDays >= MIN_DURATION_DAYS) confidence += 0.20;
        else confidence += 0.10;

        // Angle component (max 20%)
        if (angle >= 20 && angle <= 50) confidence += 0.20;
        else if (angle >= CONVERGENCE_ANGLE_MIN && angle <= CONVERGENCE_ANGLE_MAX) confidence += 0.15;
        else confidence += 0.05;

        // Volume component (max 20%)
        if (volumeAnalysis.trend.equals("decreasing")) confidence += 0.20;
        else if (volumeAnalysis.trend.equals("stable")) confidence += 0.10;

        return Math.min(confidence, 1.0);
    }

    private double calculateBreakoutTarget(double currentPrice, double height, TriangleType type) {
        // Target is typically the height of the triangle projected from breakout point
        if (type == TriangleType.ASCENDING) {
            return currentPrice + height;
        } else if (type == TriangleType.DESCENDING) {
            return currentPrice - height;
        } else {
            // Symmetrical - assume breakout direction based on position
            return currentPrice + height; // Default to upside
        }
    }

    private double calculateStopLoss(double apexPrice, TriangleType type) {
        // Stop loss at opposite side of apex
        double margin = apexPrice * 0.02; // 2% margin
        if (type == TriangleType.ASCENDING) {
            return apexPrice - margin;
        } else if (type == TriangleType.DESCENDING) {
            return apexPrice + margin;
        } else {
            return apexPrice; // At apex for symmetrical
        }
    }

    private String formatTimestamp(long timestamp) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(timestamp * 1000));
    }
}
