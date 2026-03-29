package com.dtech.kitecon.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a normalized Elliott Wave scenario with scoring and validation metrics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedScenario {

    /**
     * Unique scenario identifier (e.g., "S1", "S2").
     */
    private String id;

    /**
     * Human-readable label for the scenario.
     */
    private String label;

    /**
     * Full direction label derived from WaveScenario
     * (e.g., "Bullish", "Bearish", "Neutral").
     */
    private String directionLabel;

    /**
     * Original score before normalization.
     */
    private int rawScore;

    /**
     * Normalized score as a percentage (0–100).
     */
    private double normalizedPct;

    /**
     * Flag indicating if this scenario has the highest normalized score.
     */
    @JsonProperty("is_leading")
    private boolean isLeading;

    /**
     * Floor or invalidation price for this scenario.
     */
    private double scenarioInvalidation;

    /**
     * Market bias represented by this scenario.
     * Values: "BULL", "BEAR", or "NEUTRAL".
     */
    private String bias;

    /**
     * Target price for this scenario (nullable).
     */
    private Double target;
}
