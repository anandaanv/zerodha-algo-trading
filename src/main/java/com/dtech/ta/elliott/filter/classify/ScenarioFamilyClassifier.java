package com.dtech.ta.elliott.filter.classify;

import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.ScenarioCluster;
import com.dtech.ta.elliott.filter.domain.ScenarioFamilyCandidate;

import java.util.List;

public interface ScenarioFamilyClassifier {
    List<ScenarioFamilyCandidate> classify(List<ScenarioCluster> clusters, FilterConfig config);
}
