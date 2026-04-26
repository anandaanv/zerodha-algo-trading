package com.dtech.ta.elliott;

import com.dtech.ta.elliott.filter.domain.FilteredScenarioSet;

public record VerifiedElliottResult(
        FilteredScenarioSet filteredScenarioSet,
        String aiRawResponse,
        boolean aiConfirmed,
        String aiReasoning,
        double aiConfidence
) {}
