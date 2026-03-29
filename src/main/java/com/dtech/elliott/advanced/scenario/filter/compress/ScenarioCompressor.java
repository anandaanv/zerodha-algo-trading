package com.dtech.elliott.advanced.scenario.filter.compress;

import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.FilteredScenarioSet;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioConflictSet;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioFamilyCandidate;

import java.util.List;

public interface ScenarioCompressor {
    FilteredScenarioSet compress(
            List<ScenarioFamilyCandidate> scoredFamilies,
            ScenarioConflictSet conflictSet,
            FilterConfig config
    );
}
