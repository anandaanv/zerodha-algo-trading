package com.dtech.ta.elliott.filter.api;

import com.dtech.ta.elliott.scenario.Scenario;
import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.FilteredScenarioSet;

import java.util.List;

public interface ScenarioFilterEngine {
    FilteredScenarioSet filter(List<Scenario> rawScenarios, FilterConfig config);
}
