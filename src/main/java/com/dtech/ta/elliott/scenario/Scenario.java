package com.dtech.ta.elliott.scenario;

import com.dtech.ta.elliott.PatternMatch;
import com.dtech.ta.elliott.WaveScenario;
import com.dtech.ta.elliott.scenario.ScenarioStatus;

import java.util.List;
import java.util.Map;

public record Scenario(
        String id,
        String symbol,
        String anchorTimeframe,
        WaveScenario.ScenarioDirection direction,
        double invalidationLevel,
        String invalidationReason,
        ScenarioStatus status,
        double totalScore,
        List<PatternMatch> alignedPatterns,
        List<PatternMatch> conflictingPatterns,
        List<PatternMatch> relevantPatterns,
        Map<String, String> indicatorAlignment,
        List<WaveScenario.DecisionPoint> decisionPoints,
        List<String> statusReasons
) {}
