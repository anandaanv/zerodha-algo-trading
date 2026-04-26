package com.dtech.ta.elliott.filter.domain;

import java.util.List;

public record HumanResearchSummary(
        String symbol,
        String anchorTimeframe,
        String marketStateSummary,
        List<String> leadingScenarioSummary,
        List<String> alternateScenarioSummary,
        List<String> actionHandlingNotes,
        List<String> invalidationNotes
) {}
