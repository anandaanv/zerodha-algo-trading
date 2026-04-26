package com.dtech.ta.elliott.filter.domain;

import java.util.List;

public record ScenarioCluster(
        ScenarioSignature signature,
        List<NormalizedScenario> members,
        double aggregatedStructuralScore,
        double aggregatedTradeUtilityScore,
        double aggregatedConfluenceScore,
        double aggregatedMomentumScore,
        List<String> mergedSupportingPatterns,
        List<String> mergedSupportingStructures,
        List<String> mergedReasonCodes,
        List<String> mergedExplanation
) {}
