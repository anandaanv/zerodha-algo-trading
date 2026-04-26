package com.dtech.ta.elliott.filter.domain;

import java.util.List;

public record FilteredScenarioSet(
        String symbol,
        String anchorTimeframe,
        ScenarioFamilyCandidate leadingScenario,
        List<ScenarioFamilyCandidate> activeAlternates,
        List<ScenarioFamilyCandidate> weakAlternates,
        List<ScenarioFamilyCandidate> invalidatedFamilies,
        ScenarioConflictSet conflictSet,
        HumanResearchSummary humanSummary,
        ReasoningPayloadCompression reasoningPayloadCompression
) {}
