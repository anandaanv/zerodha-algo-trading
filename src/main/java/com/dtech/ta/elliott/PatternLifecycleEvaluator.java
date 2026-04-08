package com.dtech.ta.elliott;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates whether each PatternMatch is still active or should be discarded.
 *
 * A pattern is expired if ANY of these are true:
 *  1. EXPLICIT   — status == INVALIDATED
 *  2. TARGET_HIT — price has already reached the measured target
 *  3. SL_HIT     — price broke the invalidation level (or support/resistance SL)
 *  4. TIME_SL    — currentBar > lastPivotBar + 2 * formationLength
 *  5. PROXIMITY  — all key levels are more than 30% from current price
 */
@Component
public class PatternLifecycleEvaluator {

    private static final double PROXIMITY_THRESHOLD = 0.30;

    /**
     * Filter the given list to only active patterns.
     * Sets invalidationReason on discarded patterns before removing them.
     */
    public List<PatternMatch> filterActive(List<PatternMatch> patterns, int currentBarIndex, double currentPrice) {
        List<PatternMatch> active = new ArrayList<>();
        for (PatternMatch p : patterns) {
            PatternMatch.InvalidationReason reason = getInvalidationReason(p, currentBarIndex, currentPrice);
            if (reason == null) {
                active.add(p);
            } else {
                p.setInvalidationReason(reason);
            }
        }
        return active;
    }

    /** Returns the reason this pattern should be discarded, or null if it is still active. */
    public PatternMatch.InvalidationReason getInvalidationReason(PatternMatch p, int currentBarIndex, double currentPrice) {

        // 1. Explicitly invalidated
        if (p.getStatus() == PatternStatus.INVALIDATED) {
            return PatternMatch.InvalidationReason.EXPLICIT;
        }

        // 2. Target hit
        if (isTargetHit(p, currentPrice)) {
            return PatternMatch.InvalidationReason.TARGET_HIT;
        }

        // 3. SL hit (explicit invalidation level, or support/resistance breach)
        if (isSlHit(p, currentPrice)) {
            return PatternMatch.InvalidationReason.SL_HIT;
        }

        // 4. Time SL — only applies if pivotBarIndices is populated
        if (currentBarIndex > 0 && isTimeSLHit(p, currentBarIndex)) {
            return PatternMatch.InvalidationReason.TIME_SL;
        }

        // 5. Proximity — pattern has no relevant key levels near current price
        if (currentPrice > 0 && isOutOfProximity(p, currentPrice)) {
            return PatternMatch.InvalidationReason.PROXIMITY;
        }

        return null; // still active
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private boolean isTargetHit(PatternMatch p, double currentPrice) {
        Double target = p.getTarget();
        if (target == null) return false;
        // Use isBullish() / isBearish() methods on PatternMatch
        if (p.isBullish()) return currentPrice >= target;
        if (p.isBearish()) return currentPrice <= target;
        return false;
    }

    private boolean isSlHit(PatternMatch p, double currentPrice) {
        // Explicit invalidation level takes priority
        Double inv = p.getInvalidation();
        if (inv != null) {
            if (p.isBullish()) return currentPrice < inv;
            if (p.isBearish()) return currentPrice > inv;
        }
        // Fall back to support/resistance breach
        if (p.isBullish() && p.getSupport() != null) {
            return currentPrice < p.getSupport() * 0.99; // 1% buffer
        }
        if (p.isBearish() && p.getResistance() != null) {
            return currentPrice > p.getResistance() * 1.01;
        }
        return false;
    }

    private boolean isTimeSLHit(PatternMatch p, int currentBarIndex) {
        List<Integer> bars = p.getPivotBarIndices();
        if (bars == null || bars.size() < 2) return false;

        int firstBar = bars.stream().mapToInt(Integer::intValue).min().orElse(0);
        int lastBar  = bars.stream().mapToInt(Integer::intValue).max().orElse(0);
        int formationLength = lastBar - firstBar;

        if (formationLength <= 0) return false;

        // Time SL fires at lastPivotBar + 2 * formationLength
        int deadline = lastBar + 2 * formationLength;
        return currentBarIndex > deadline;
    }

    private boolean isOutOfProximity(PatternMatch p, double currentPrice) {
        // Collect all defined key levels
        List<Double> levels = new ArrayList<>();
        if (p.getSupport()    != null) levels.add(p.getSupport());
        if (p.getResistance() != null) levels.add(p.getResistance());
        if (p.getNeckline()   != null) levels.add(p.getNeckline());
        if (p.getTarget()     != null) levels.add(p.getTarget());

        if (levels.isEmpty()) return false; // no levels to check — keep it

        // Keep pattern if ANY level is within proximity threshold of current price
        for (Double level : levels) {
            double dist = Math.abs(level - currentPrice) / currentPrice;
            if (dist <= PROXIMITY_THRESHOLD) return false;
        }
        return true; // all levels are far away
    }
}
