package com.dtech.elliott.advanced.scenario.filter.summary;

import com.dtech.elliott.advanced.scenario.filter.domain.FilteredScenarioSet;
import com.dtech.elliott.advanced.scenario.filter.domain.HumanResearchSummary;

public interface HumanResearchSummaryBuilder {
    HumanResearchSummary build(FilteredScenarioSet filteredScenarioSet);
}
