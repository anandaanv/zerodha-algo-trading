package com.dtech.ta.elliott.filter.dedupe;

import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.NormalizedScenario;
import com.dtech.ta.elliott.filter.domain.ScenarioSignature;

public class DefaultSignatureBuilder implements SignatureBuilder {

    @Override
    public ScenarioSignature build(NormalizedScenario scenario, FilterConfig config) {
        long bucket = Math.round(scenario.primaryInvalidationLevel() / config.invalidationBucketSize());
        return new ScenarioSignature(
                scenario.symbol(),
                scenario.anchorTimeframe(),
                scenario.directionalBias(),
                scenario.expectedMoveType(),
                scenario.dominantStructureFamily(),
                bucket,
                scenario.decisionZoneNearby(),
                scenario.triggerEligible()
        );
    }
}
