package com.dtech.elliott.advanced.scenario.filter.normalize;

import com.dtech.elliott.advanced.domain.scenario.Scenario;
import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.NormalizedScenario;

import java.util.List;

public interface ScenarioNormalizer {
    List<NormalizedScenario> normalize(List<Scenario> scenarios, FilterConfig config);
}
