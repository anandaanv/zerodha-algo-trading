package com.dtech.elliott.advanced.scenario.filter.summary;

import com.dtech.elliott.advanced.scenario.filter.domain.*;

import java.util.*;

public class DefaultReasoningPayloadCompressionBuilder implements ReasoningPayloadCompressionBuilder {

    @Override
    public ReasoningPayloadCompression build(FilteredScenarioSet set) {
        if (set == null) {
            return new ReasoningPayloadCompression("", "", Map.of(), List.of(), List.of(), List.of(), List.of());
        }

        String symbol = set.symbol() != null ? set.symbol() : "";
        String tf = set.anchorTimeframe() != null ? set.anchorTimeframe() : "";

        Map<String, Object> leadingMap = buildFamilyMap(set.leadingScenario());

        List<Map<String, Object>> alternatesMap = new ArrayList<>();
        if (set.activeAlternates() != null) {
            for (ScenarioFamilyCandidate alt : set.activeAlternates()) {
                alternatesMap.add(buildFamilyMap(alt));
            }
        }

        List<Map<String, Object>> decisionZones = List.of(
                Map.of("note", "See leading scenario for decision zone context")
        );

        List<Map<String, Object>> triggerList = new ArrayList<>();
        if (set.leadingScenario() != null && set.leadingScenario().triggerEligible()) {
            String firstPattern = set.leadingScenario().supportingPatterns() != null
                    && !set.leadingScenario().supportingPatterns().isEmpty()
                    ? set.leadingScenario().supportingPatterns().get(0) : "N/A";
            triggerList.add(Map.of(
                    "familyId", set.leadingScenario().id(),
                    "direction", set.leadingScenario().directionalBias().name(),
                    "pattern", firstPattern
            ));
        }
        if (set.activeAlternates() != null) {
            for (ScenarioFamilyCandidate alt : set.activeAlternates()) {
                if (alt.triggerEligible()) {
                    String firstPattern = alt.supportingPatterns() != null
                            && !alt.supportingPatterns().isEmpty()
                            ? alt.supportingPatterns().get(0) : "N/A";
                    triggerList.add(Map.of(
                            "familyId", alt.id(),
                            "direction", alt.directionalBias().name(),
                            "pattern", firstPattern
                    ));
                }
            }
        }

        List<String> contradictions = set.conflictSet() != null
                ? set.conflictSet().explanation() : List.of();

        return new ReasoningPayloadCompression(symbol, tf, leadingMap,
                List.copyOf(alternatesMap), decisionZones, List.copyOf(triggerList), contradictions);
    }

    private Map<String, Object> buildFamilyMap(ScenarioFamilyCandidate family) {
        if (family == null) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", family.id());
        map.put("familyType", family.familyType().name());
        map.put("direction", family.directionalBias().name());
        map.put("invalidation", family.primaryInvalidationLevel());
        map.put("triggerEligible", family.triggerEligible());
        map.put("patterns", family.supportingPatterns());
        map.put("score", family.score() != null ? family.score().finalRankScore() : 0.0);
        return Collections.unmodifiableMap(map);
    }
}
