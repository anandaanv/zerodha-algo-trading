package com.dtech.ta.elliott.filter.dedupe;

import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.NormalizedScenario;
import com.dtech.ta.elliott.filter.domain.ScenarioCluster;

import java.util.List;

public interface ScenarioDeduplicator {
    List<ScenarioCluster> deduplicate(List<NormalizedScenario> scenarios, FilterConfig config);
}
