package com.dtech.elliott.advanced.scenario.filter.prune;

import com.dtech.elliott.advanced.domain.scenario.Scenario;
import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;

import java.util.List;

public interface HardPruner {
    List<Scenario> prune(List<Scenario> rawScenarios, FilterConfig config);
}
