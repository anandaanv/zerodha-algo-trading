package com.dtech.elliott.advanced.scenario.filter.dedupe;

import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.NormalizedScenario;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioSignature;

public interface SignatureBuilder {
    ScenarioSignature build(NormalizedScenario scenario, FilterConfig config);
}
