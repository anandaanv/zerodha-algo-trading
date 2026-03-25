package com.dtech.elliott.advanced.scenario.filter.dedupe;

import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.NormalizedScenario;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioSignature;

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
