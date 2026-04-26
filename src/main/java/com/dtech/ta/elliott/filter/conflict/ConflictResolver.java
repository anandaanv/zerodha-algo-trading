package com.dtech.ta.elliott.filter.conflict;

import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.ScenarioConflictSet;
import com.dtech.ta.elliott.filter.domain.ScenarioFamilyCandidate;

import java.util.List;

public interface ConflictResolver {
    ScenarioConflictSet resolve(List<ScenarioFamilyCandidate> families, FilterConfig config);
}
