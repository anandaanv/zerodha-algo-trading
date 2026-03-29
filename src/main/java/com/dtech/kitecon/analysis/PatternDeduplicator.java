package com.dtech.kitecon.analysis;

import com.dtech.ta.elliott.PatternMatch;
import com.dtech.ta.elliott.PatternStatus;
import com.dtech.ta.elliott.PatternType;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PatternDeduplicator {

    private static final double OVERLAP_THRESHOLD = 0.70;

    /**
     * Minimum confidence for a BUILDING pattern to be retained.
     * Single-pivot early-stage BUILDING patterns (conf=25) are pure noise — dropped here.
     * Only BUILDING patterns with two or more pivots confirmed (conf≥40) are kept.
     */
    private static final double MIN_BUILDING_CONFIDENCE = 40.0;

    /**
     * Deduplicates a list of PatternMatch objects by:
     * 0. Pre-filtering: drop low-confidence BUILDING patterns (single-pivot noise)
     * 1. Grouping by (timeframe + pattern type)
     * 2. Removing duplicates within each group (>70% price zone overlap)
     * 3. Enforcing hard caps per (timeframe, pattern type)
     */
    public List<PatternMatch> deduplicate(List<PatternMatch> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return new ArrayList<>();
        }

        // Step 0: Drop single-pivot early BUILDING noise
        List<PatternMatch> relevant = patterns.stream()
                .filter(this::isRelevant)
                .collect(Collectors.toList());

        // Step 1: Group by (timeframe, patternType)
        Map<GroupKey, List<PatternMatch>> groupedByTfAndType = relevant.stream()
                .collect(Collectors.groupingBy(p -> new GroupKey(p.getTimeframe(), p.getType())));

        // Step 2: Within each group, remove duplicates
        List<PatternMatch> afterDedup = groupedByTfAndType.values().stream()
                .flatMap(groupPatterns -> removeDuplicatesWithinGroup(groupPatterns).stream())
                .collect(Collectors.toList());

        // Step 3: Enforce hard caps per (timeframe, patternType)
        return enforceHardCaps(afterDedup);
    }

    /**
     * A pattern is relevant if:
     * - It is WATCHING or CONFIRMED (always kept), OR
     * - It is BUILDING with confidence ≥ 40 (two or more pivots confirmed, actionable setup)
     *
     * This drops single-pivot observations like "saw one low, could be a double bottom"
     * which have confidence=25 and no actionable intelligence.
     */
    private boolean isRelevant(PatternMatch p) {
        if (p.getStatus() == PatternStatus.WATCHING
                || p.getStatus() == PatternStatus.CONFIRMED) {
            return true;
        }
        // BUILDING: only keep if structure is meaningful (≥2 pivots confirmed)
        return p.getConfidence() >= MIN_BUILDING_CONFIDENCE;
    }

    /**
     * Removes duplicates within a single (timeframe, patternType) group.
     * Keeps the pattern with highest confidence when duplicates are found.
     */
    private List<PatternMatch> removeDuplicatesWithinGroup(List<PatternMatch> groupPatterns) {
        List<PatternMatch> result = new ArrayList<>();

        for (PatternMatch candidate : groupPatterns) {
            boolean isDuplicate = false;

            for (int i = 0; i < result.size(); i++) {
                PatternMatch existing = result.get(i);
                double overlap = calculatePriceZoneOverlap(candidate, existing);

                if (overlap > OVERLAP_THRESHOLD) {
                    // Duplicate found
                    isDuplicate = true;

                    // Keep the one with higher confidence, or prefer WATCHING over BUILDING if tied
                    if (candidate.getConfidence() > existing.getConfidence()) {
                        result.set(i, candidate);
                    } else if (candidate.getConfidence() == existing.getConfidence()) {
                        if (isPreferredStatus(candidate.getStatus(), existing.getStatus())) {
                            result.set(i, candidate);
                        }
                    }
                    break;
                }
            }

            if (!isDuplicate) {
                result.add(candidate);
            }
        }

        return result;
    }

    /**
     * Enforces hard caps per (timeframe, patternType).
     * Sorts by confidence descending, then applies max counts.
     */
    private List<PatternMatch> enforceHardCaps(List<PatternMatch> patterns) {
        Map<GroupKey, List<PatternMatch>> groupedByTfAndType = patterns.stream()
                .collect(Collectors.groupingBy(p -> new GroupKey(p.getTimeframe(), p.getType())));

        return groupedByTfAndType.entrySet().stream()
                .flatMap(entry -> applyCap(entry.getKey().patternType, entry.getValue()).stream())
                .collect(Collectors.toList());
    }

    /**
     * Applies the hard cap for a specific pattern type.
     * Sorts by confidence descending, keeps only top N patterns.
     */
    private List<PatternMatch> applyCap(PatternType patternType, List<PatternMatch> patterns) {
        int maxCount = getMaxCountForType(patternType);

        return patterns.stream()
                .sorted((p1, p2) -> Double.compare(p2.getConfidence(), p1.getConfidence()))
                .limit(maxCount)
                .collect(Collectors.toList());
    }

    /**
     * Returns the maximum number of patterns allowed per (timeframe, patternType).
     * Kept tight: we only want the highest-quality current-context setups.
     */
    private int getMaxCountForType(PatternType patternType) {
        return switch (patternType) {
            case SYMMETRICAL_TRIANGLE, ASCENDING_TRIANGLE, DESCENDING_TRIANGLE -> 1;
            case RISING_WEDGE, FALLING_WEDGE -> 1;
            case ASCENDING_CHANNEL, DESCENDING_CHANNEL, HORIZONTAL_CHANNEL -> 1;
            case DOUBLE_TOP, DOUBLE_BOTTOM -> 1;
            case TRIPLE_TOP, TRIPLE_BOTTOM -> 1;
            case HEAD_AND_SHOULDERS, INVERTED_HEAD_AND_SHOULDERS -> 1;
            case BULL_FLAG, BEAR_FLAG, BULL_PENNANT, BEAR_PENNANT -> 1;
            case LEADING_DIAGONAL, ENDING_DIAGONAL -> 1;
            default -> 1;
        };
    }

    /**
     * Calculates the price zone overlap between two patterns.
     * Returns overlap percentage (0.0 to 1.0).
     *
     * Price zone: [min(support, resistance), max(support, resistance)]
     * If support/resistance both null, try neckline + target.
     * If no price levels, return 0.0 (no overlap).
     */
    private double calculatePriceZoneOverlap(PatternMatch p1, PatternMatch p2) {
        double[] zone1 = getPriceZone(p1);
        double[] zone2 = getPriceZone(p2);

        if (zone1 == null || zone2 == null) {
            return 0.0;
        }

        double low1 = zone1[0], high1 = zone1[1];
        double low2 = zone2[0], high2 = zone2[1];

        double overlapLow = Math.max(low1, low2);
        double overlapHigh = Math.min(high1, high2);

        if (overlapHigh < overlapLow) {
            return 0.0; // No overlap
        }

        double overlapSize = overlapHigh - overlapLow;
        double totalSize = Math.max(high1, high2) - Math.min(low1, low2);

        return totalSize > 0 ? overlapSize / totalSize : 0.0;
    }

    /**
     * Extracts the price zone from a pattern.
     * Returns [low, high] or null if no price levels are available.
     */
    private double[] getPriceZone(PatternMatch pattern) {
        Double support = pattern.getSupport();
        Double resistance = pattern.getResistance();

        // Try support/resistance first
        if (support != null && resistance != null) {
            return new double[]{Math.min(support, resistance), Math.max(support, resistance)};
        }

        // Fall back to neckline/target
        Double neckline = pattern.getNeckline();
        Double target = pattern.getTarget();

        if (neckline != null && target != null) {
            return new double[]{Math.min(neckline, target), Math.max(neckline, target)};
        }

        // No price levels available
        return null;
    }

    /**
     * Determines if status1 is preferred over status2 when confidence is tied.
     * Prefers WATCHING > BUILDING.
     */
    private boolean isPreferredStatus(PatternStatus status1, PatternStatus status2) {
        if (status1 == PatternStatus.WATCHING && status2 != PatternStatus.WATCHING) {
            return true;
        }
        return false;
    }

    /**
     * Returns the count of patterns before deduplication.
     */
    public int countBeforeDedup(List<PatternMatch> patterns) {
        return patterns != null ? patterns.size() : 0;
    }

    /**
     * Returns the count of patterns after deduplication and cap enforcement.
     */
    public int countAfterDedup(List<PatternMatch> patterns) {
        return deduplicate(patterns).size();
    }

    /**
     * Key class for grouping by (timeframe, patternType).
     */
    private static class GroupKey {
        private final String timeframe;
        private final PatternType patternType;

        GroupKey(String timeframe, PatternType patternType) {
            this.timeframe = timeframe;
            this.patternType = patternType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GroupKey groupKey = (GroupKey) o;
            return Objects.equals(timeframe, groupKey.timeframe) && patternType == groupKey.patternType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(timeframe, patternType);
        }
    }
}
