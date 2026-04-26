package com.dtech.ta.elliott.filter.dedupe;

import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.NormalizedScenario;
import com.dtech.ta.elliott.filter.domain.ScenarioSignature;

public interface SignatureBuilder {
    ScenarioSignature build(NormalizedScenario scenario, FilterConfig config);
}
