package com.dtech.ta.elliott;

import java.util.List;

/**
 * Classifies a sub-sequence of pivots as an impulse (5-wave) or corrective (3-wave) structure.
 *
 * Used to assess whether the PRIOR DECLINE was a 5-wave impulse (making a simple
 * double-bottom less likely) or a 3-wave zigzag (making corrective alternatives more likely).
 *
 * Logic:
 *   - Count alternating pivots between startIdx and endIdx (inclusive).
 *   - 6+ pivots (5 swings) → IMPULSE_5 candidate
 *   - 4 pivots  (3 swings) → ZIGZAG_3 candidate
 *   - Additional Fibonacci checks tighten the classification.
 */
public class WaveStructureClassifier {

    public enum Structure {
        IMPULSE_5,   // Five clear waves — motive structure
        ZIGZAG_3,    // Three-wave ABC — corrective structure
        UNKNOWN      // Not enough pivots or ambiguous
    }

    /**
     * Classifies the pivot sequence from startIdx to endIdx (inclusive).
     *
     * @param pivots   full pivot list
     * @param startIdx index of the move's origin pivot
     * @param endIdx   index of the move's terminal pivot
     * @return Structure classification
     */
    public Structure classify(List<EnrichedPivot> pivots, int startIdx, int endIdx) {
        if (startIdx < 0 || endIdx < 0 || endIdx <= startIdx || endIdx >= pivots.size()) {
            return Structure.UNKNOWN;
        }

        List<EnrichedPivot> sub = pivots.subList(startIdx, endIdx + 1);
        int count = sub.size();

        if (count >= 6) {
            // Five swings → impulse-like; do a basic Fibonacci sanity check
            return validateImpulse(sub) ? Structure.IMPULSE_5 : Structure.ZIGZAG_3;
        }
        if (count == 4) {
            // Three swings → zigzag-like
            return Structure.ZIGZAG_3;
        }

        return Structure.UNKNOWN;
    }

    /**
     * Convenience: classify the most recent decline in a pivot list.
     * Scans backwards to find the last peak-to-trough sequence.
     */
    public Structure classifyLastDecline(List<EnrichedPivot> pivots) {
        if (pivots == null || pivots.size() < 4) return Structure.UNKNOWN;

        // Find last trough
        int troughIdx = -1;
        for (int i = pivots.size() - 1; i >= 0; i--) {
            if (pivots.get(i).isLow()) { troughIdx = i; break; }
        }
        if (troughIdx <= 0) return Structure.UNKNOWN;

        // Find peak before trough
        int peakIdx = -1;
        for (int i = troughIdx - 1; i >= 0; i--) {
            if (pivots.get(i).isHigh()) { peakIdx = i; break; }
        }
        if (peakIdx < 0) return Structure.UNKNOWN;

        return classify(pivots, peakIdx, troughIdx);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Basic impulse validation on a 6-pivot sequence:
     *   - Wave 3 must not be the shortest impulse wave
     *   - Wave 4 must not overlap wave 1 territory (for standard impulse)
     * Returns true if the sequence looks more like an impulse than a complex correction.
     */
    private boolean validateImpulse(List<EnrichedPivot> sub) {
        if (sub.size() < 6) return false;

        EnrichedPivot p0 = sub.get(0);
        EnrichedPivot p1 = sub.get(1);
        EnrichedPivot p2 = sub.get(2);
        EnrichedPivot p3 = sub.get(3);
        EnrichedPivot p4 = sub.get(4);
        // p5 = sub.get(5) is the terminal pivot

        boolean declining = p0.getPrice() > p1.getPrice(); // true = bearish impulse

        if (declining) {
            double w1 = p0.getPrice() - p1.getPrice();
            double w3 = p2.getPrice() - p3.getPrice();
            double w4HardFloor = p1.getPrice(); // wave 4 must stay above wave 1 low in standard impulse

            // Wave 3 should not be shortest
            if (w3 < w1 * 0.5) return false;
            // Wave 4 must not overlap wave 1 territory (Elliott rule 2)
            if (p4.getPrice() < w4HardFloor) return false;
            return true;
        } else {
            double w1 = p1.getPrice() - p0.getPrice();
            double w3 = p3.getPrice() - p2.getPrice();
            double w4HardFloor = p1.getPrice(); // wave 4 must stay below wave 1 high

            if (w3 < w1 * 0.5) return false;
            if (p4.getPrice() > w4HardFloor) return false;
            return true;
        }
    }
}
