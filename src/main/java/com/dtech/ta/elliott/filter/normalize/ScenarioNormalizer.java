package com.dtech.ta.elliott.filter.normalize;

import com.dtech.ta.elliott.scenario.Scenario;
import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.NormalizedScenario;

import java.util.List;

public interface ScenarioNormalizer {
    List<NormalizedScenario> normalize(List<Scenario> scenarios, FilterConfig config);
}
