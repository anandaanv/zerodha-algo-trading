package com.dtech.ta.elliott.scenario;

import com.dtech.ta.elliott.scenario.ScoredScenario;
import com.dtech.ta.elliott.WaveScenario;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ScoredScenarioAdapter {

    public static Scenario toScenario(ScoredScenario ss, String symbol, String anchorTimeframe) {
        WaveScenario ws = ss.getScenario();
        if (ws == null) {
            throw new IllegalArgumentException("WaveScenario must not be null");
        }
        return new Scenario(
                ws.getId(),
                symbol,
                anchorTimeframe,
                ws.getDirection(),
                ws.getScenarioInvalidation(),
                ws.getInvalidationReason(),
                ss.getStatus(),
                ss.getTotalScore(),
                orEmpty(ws.getAlignedPatterns()),
                orEmpty(ws.getConflictingPatterns()),
                orEmpty(ws.getRelevantPatterns()),
                orEmptyMap(ws.getIndicatorAlignment()),
                orEmpty(ws.getDecisionPoints()),
                orEmpty(ss.getStatusReasons())
        );
    }

    public static List<Scenario> toScenarios(List<ScoredScenario> list, String symbol, String anchorTimeframe) {
        if (list == null || list.isEmpty()) return List.of();
        return list.stream()
                .map(ss -> toScenario(ss, symbol, anchorTimeframe))
                .toList();
    }

    private static <T> List<T> orEmpty(List<T> l) {
        return l != null ? l : List.of();
    }

    private static <K, V> Map<K, V> orEmptyMap(Map<K, V> m) {
        return m != null ? m : Map.of();
    }
}
