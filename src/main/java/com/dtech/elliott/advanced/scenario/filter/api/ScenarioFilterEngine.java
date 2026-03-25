package com.dtech.elliott.advanced.scenario.filter.api;

import com.dtech.elliott.advanced.domain.scenario.Scenario;
import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.FilteredScenarioSet;

import java.util.List;

public interface ScenarioFilterEngine {
    FilteredScenarioSet filter(List<Scenario> rawScenarios, FilterConfig config);
}
