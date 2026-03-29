package com.dtech.ta.elliott;

import com.dtech.elliott.advanced.scenario.filter.domain.FilteredScenarioSet;

public record VerifiedElliottResult(
        FilteredScenarioSet filteredScenarioSet,
        String aiRawResponse,
        boolean aiConfirmed,
        String aiReasoning,
        double aiConfidence
) {}
