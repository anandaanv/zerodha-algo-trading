package com.dtech.elliott.advanced.scenario.filter.domain;

import com.dtech.elliott.advanced.common.enums.Direction;
import com.dtech.elliott.advanced.common.enums.ExpectedMoveType;
import com.dtech.elliott.advanced.common.enums.StructureFamily;

public record ScenarioSignature(
        String symbol,
        String anchorTimeframe,
        Direction directionalBias,
        ExpectedMoveType expectedMoveType,
        StructureFamily dominantStructureFamily,
        long roundedInvalidationBucket,
        boolean decisionZoneNearby,
        boolean triggerEligible
) {}
