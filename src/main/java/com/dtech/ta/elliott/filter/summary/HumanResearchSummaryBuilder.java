package com.dtech.ta.elliott.filter.summary;

import com.dtech.ta.elliott.filter.domain.FilteredScenarioSet;
import com.dtech.ta.elliott.filter.domain.HumanResearchSummary;

public interface HumanResearchSummaryBuilder {
    HumanResearchSummary build(FilteredScenarioSet filteredScenarioSet);
}
