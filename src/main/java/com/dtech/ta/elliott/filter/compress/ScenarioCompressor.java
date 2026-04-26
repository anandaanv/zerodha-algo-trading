package com.dtech.ta.elliott.filter.compress;

import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.FilteredScenarioSet;
import com.dtech.ta.elliott.filter.domain.ScenarioConflictSet;
import com.dtech.ta.elliott.filter.domain.ScenarioFamilyCandidate;

import java.util.List;

public interface ScenarioCompressor {
    FilteredScenarioSet compress(
            List<ScenarioFamilyCandidate> scoredFamilies,
            ScenarioConflictSet conflictSet,
            FilterConfig config
    );
}
