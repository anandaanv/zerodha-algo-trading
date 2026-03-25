package com.dtech.elliott.advanced.scenario.filter.domain;

import com.dtech.elliott.advanced.common.enums.Direction;
import com.dtech.elliott.advanced.common.enums.ExpectedMoveType;
import com.dtech.elliott.advanced.common.enums.StructureFamily;
import com.dtech.ta.elliott.scenario.ScenarioStatus;

import java.util.List;
import java.util.Map;

public record NormalizedScenario(
        String sourceScenarioId,
        String symbol,
        String anchorTimeframe,
        Direction directionalBias,
        ExpectedMoveType expectedMoveType,
        StructureFamily dominantStructureFamily,
        List<String> supportingStructureTypes,
        List<String> supportingPatternTypes,
        double primaryInvalidationLevel,
        double invalidationTolerance,
        double targetReferenceLevel,
        double confluenceScore,
        double structuralScore,
        double momentumScore,
        double ambiguityScore,
        double tradeUtilityScore,
        boolean decisionZoneNearby,
        boolean triggerEligible,
        ScenarioStatus sourceStatus,
        Map<String, Double> scoreComponents,
        List<String> explanation,
        List<String> reasonCodes
) {}
