package com.dtech.elliott.advanced.scenario.filter.conflict;

import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioConflictSet;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioFamilyCandidate;

import java.util.List;

public interface ConflictResolver {
    ScenarioConflictSet resolve(List<ScenarioFamilyCandidate> families, FilterConfig config);
}
