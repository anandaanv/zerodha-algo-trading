package com.dtech.kitecon.service.copilot.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * Phase 1 (SCAN) response — AI reports what it observes on the chart.
 * No trade proposals, no hypotheses. Pure detection.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ObservationResponse extends AIResponse {

    /** Whether the skill's target pattern/wave was detected */
    private Boolean patternDetected;

    /** What was detected: "triangle", "wave_4", "double_bottom", etc. */
    private String patternType;

    /** HIGH | MEDIUM | LOW */
    private String confidence;

    /** What was observed — structural details */
    private String structuralDetails;

    /** Current stage if detectable, e.g. "D_COMPLETE", "corrective" */
    private String stage;

    /** Key price levels identified */
    private List<KeyLevel> keyLevels;

    /** Coordinates for drawing the pattern on TradingView */
    private List<DrawingPoint> drawingPoints;

    /**
     * TradingView drawing tool to use: triangle_pattern, elliott_impulse_wave,
     * elliott_correction, elliott_triangle_wave, head_and_shoulders,
     * parallel_channel, fib_retracement, xabcd_pattern, trend_line
     */
    private String drawingType;

    /** Which timeframe this observation applies to */
    private String timeframe;

    /** Contradictions or caveats found */
    private List<String> contradictions;

    @Data
    @SuperBuilder
    @NoArgsConstructor
    public static class KeyLevel {
        private Double price;
        private String label;
    }

    @Data
    @SuperBuilder
    @NoArgsConstructor
    public static class DrawingPoint {
        /** Unix timestamp in seconds */
        private Long time;
        private Double price;
        private String label;
    }
}
