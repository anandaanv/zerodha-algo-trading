package com.dtech.ta.elliott.filter.score;

import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.ScenarioFamilyCandidate;

import java.util.List;

public interface FamilyScorer {
    List<ScenarioFamilyCandidate> score(List<ScenarioFamilyCandidate> families, FilterConfig config);
}
