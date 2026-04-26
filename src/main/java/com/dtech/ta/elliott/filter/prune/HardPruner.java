package com.dtech.ta.elliott.filter.prune;

import com.dtech.ta.elliott.scenario.Scenario;
import com.dtech.ta.elliott.filter.config.FilterConfig;

import java.util.List;

public interface HardPruner {
    List<Scenario> prune(List<Scenario> rawScenarios, FilterConfig config);
}
