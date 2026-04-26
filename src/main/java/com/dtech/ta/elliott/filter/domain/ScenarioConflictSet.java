package com.dtech.ta.elliott.filter.domain;

import java.util.List;

public record ScenarioConflictSet(
        String symbol,
        String anchorTimeframe,
        List<ScenarioFamilyCandidate> bullishFamilies,
        List<ScenarioFamilyCandidate> bearishFamilies,
        List<ScenarioFamilyCandidate> neutralFamilies,
        String dominantConflictMode,
        List<String> explanation
) {}
