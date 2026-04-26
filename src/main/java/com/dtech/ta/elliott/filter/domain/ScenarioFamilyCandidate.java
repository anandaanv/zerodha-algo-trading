package com.dtech.ta.elliott.filter.domain;

import com.dtech.ta.elliott.model.Direction;
import com.dtech.ta.elliott.scenario.ScenarioStatus;

import java.util.List;
import java.util.Map;

public record ScenarioFamilyCandidate(
        String id,
        String symbol,
        String anchorTimeframe,
        ScenarioFamilyType familyType,
        Direction directionalBias,
        List<ScenarioCluster> sourceClusters,
        List<String> supportingStructures,
        List<String> supportingPatterns,
        List<String> contradictingPatterns,
        double primaryInvalidationLevel,
        double confirmationLevel,
        double projectedTargetReference,
        boolean decisionZoneNearby,
        boolean triggerEligible,
        boolean tradableNow,
        ScenarioStatus status,
        FamilyScore score,
        List<String> explanation,
        List<String> reasonCodes,
        Map<String, Object> metadata
) {}
