package com.dtech.elliott.advanced.scenario.filter.score;

import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioFamilyCandidate;

import java.util.List;

public interface FamilyScorer {
    List<ScenarioFamilyCandidate> score(List<ScenarioFamilyCandidate> families, FilterConfig config);
}
