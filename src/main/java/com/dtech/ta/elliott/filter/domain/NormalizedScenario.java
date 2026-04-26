package com.dtech.ta.elliott.filter.domain;

import com.dtech.ta.elliott.model.Direction;
import com.dtech.ta.elliott.model.ExpectedMoveType;
import com.dtech.ta.elliott.model.StructureFamily;
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
