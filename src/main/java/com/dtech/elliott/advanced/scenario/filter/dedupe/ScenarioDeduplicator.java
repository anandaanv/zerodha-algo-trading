package com.dtech.elliott.advanced.scenario.filter.dedupe;

import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.NormalizedScenario;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioCluster;

import java.util.List;

public interface ScenarioDeduplicator {
    List<ScenarioCluster> deduplicate(List<NormalizedScenario> scenarios, FilterConfig config);
}
