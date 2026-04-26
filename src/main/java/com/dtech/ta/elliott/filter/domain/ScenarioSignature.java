package com.dtech.ta.elliott.filter.domain;

import com.dtech.ta.elliott.model.Direction;
import com.dtech.ta.elliott.model.ExpectedMoveType;
import com.dtech.ta.elliott.model.StructureFamily;

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
