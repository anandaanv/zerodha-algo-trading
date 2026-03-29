package com.dtech.elliott.advanced.scenario.filter.summary;

import com.dtech.elliott.advanced.scenario.filter.domain.FilteredScenarioSet;
import com.dtech.elliott.advanced.scenario.filter.domain.ReasoningPayloadCompression;

public interface ReasoningPayloadCompressionBuilder {
    ReasoningPayloadCompression build(FilteredScenarioSet filteredScenarioSet);
}
