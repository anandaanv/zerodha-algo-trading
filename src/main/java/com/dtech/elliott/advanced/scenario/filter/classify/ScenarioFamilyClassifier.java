package com.dtech.elliott.advanced.scenario.filter.classify;

import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioCluster;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioFamilyCandidate;

import java.util.List;

public interface ScenarioFamilyClassifier {
    List<ScenarioFamilyCandidate> classify(List<ScenarioCluster> clusters, FilterConfig config);
}
