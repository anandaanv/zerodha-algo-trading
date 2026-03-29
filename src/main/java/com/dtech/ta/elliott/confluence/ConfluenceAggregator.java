package com.dtech.ta.elliott.confluence;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ConfluenceAggregator {

    /**
     * Merge overlapping price levels into confluence zones.
     * Two levels overlap if their tolerance bands intersect:
     *   level.price - level.tolerance <= zone.upperPrice  AND
     *   level.price + level.tolerance >= zone.lowerPrice
     */
    public List<ConfluenceZone> aggregate(List<PriceLevel> allLevels) {
        if (allLevels == null || allLevels.isEmpty()) return List.of();

        // Sort by price so nearby levels are adjacent
        List<PriceLevel> sorted = new ArrayList<>(allLevels);
        sorted.sort(Comparator.comparingDouble(PriceLevel::getPrice));

        // Each zone: mutable lower/upper + contributing levels
        List<ZoneAccumulator> accumulators = new ArrayList<>();

        for (PriceLevel level : sorted) {
            double lo = level.getPrice() - level.getTolerance();
            double hi = level.getPrice() + level.getTolerance();

            ZoneAccumulator matched = null;
            for (ZoneAccumulator acc : accumulators) {
                if (lo <= acc.upper && hi >= acc.lower) {
                    matched = acc;
                    break;
                }
            }

            if (matched != null) {
                matched.lower = Math.min(matched.lower, lo);
                matched.upper = Math.max(matched.upper, hi);
                matched.levels.add(level);
            } else {
                ZoneAccumulator acc = new ZoneAccumulator();
                acc.lower = lo;
                acc.upper = hi;
                acc.levels.add(level);
                accumulators.add(acc);
            }
        }

        List<ConfluenceZone> zones = new ArrayList<>();
        int idx = 1;
        for (ZoneAccumulator acc : accumulators) {
            double mid = (acc.lower + acc.upper) / 2.0;
            int factorCount = acc.levels.size();
            long distinctTypes = acc.levels.stream()
                    .map(PriceLevel::getSourceType)
                    .distinct().count();
            int factorDiversity = (int) distinctTypes;
            double score = (double) factorCount * factorDiversity;

            Set<String> types = acc.levels.stream()
                    .map(PriceLevel::getSourceType)
                    .collect(Collectors.toSet());
            String zoneType;
            if (types.size() == 1 && types.contains("HORIZONTAL_SR")) {
                zoneType = "SR_ONLY";
            } else if (types.stream().allMatch(t -> t.startsWith("FIB"))) {
                zoneType = "FIB_CLUSTER";
            } else {
                zoneType = "MIXED";
            }

            zones.add(ConfluenceZone.builder()
                    .id("CZ_" + idx++)
                    .lowerPrice(acc.lower)
                    .upperPrice(acc.upper)
                    .midPrice(mid)
                    .contributingLevels(new ArrayList<>(acc.levels))
                    .factorCount(factorCount)
                    .factorDiversity(factorDiversity)
                    .score(score)
                    .zoneType(zoneType)
                    .explanation(new ArrayList<>())
                    .build());
        }

        zones.sort(Comparator.comparingDouble(ConfluenceZone::getScore).reversed());
        return zones;
    }

    private static class ZoneAccumulator {
        double lower;
        double upper;
        List<PriceLevel> levels = new ArrayList<>();
    }
}
