package com.dtech.ta.elliott.filter.summary;

import com.dtech.ta.elliott.filter.domain.FilteredScenarioSet;
import com.dtech.ta.elliott.filter.domain.ReasoningPayloadCompression;

public interface ReasoningPayloadCompressionBuilder {
    ReasoningPayloadCompression build(FilteredScenarioSet filteredScenarioSet);
}
