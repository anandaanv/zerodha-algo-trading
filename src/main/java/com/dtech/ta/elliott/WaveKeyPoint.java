package com.dtech.ta.elliott;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A labeled wave endpoint — maps a WaveLabel (W1, W2, W3, W4, W5, WA, WB, WC)
 * to the actual pivot price and timestamp from the Elliott engine.
 *
 * Included in AdvancedElliottAnalysisResult so the frontend can display
 * a wave map alongside AI claims, and so the AI prompt can reference
 * actual prices instead of abstract labels.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaveKeyPoint {
    /** Wave label name, e.g. "W1", "W2", "W3", "W4", "W5", "WA", "WB", "WC" */
    private String label;
    /** Exact pivot price */
    private double price;
    /** Exact pivot timestamp */
    private Instant timestamp;
    /** Timeframe this wave count belongs to, e.g. "4h", "1d" */
    private String timeframe;
    /** "HIGH" or "LOW" */
    private String type;
}
