package com.dtech.elliott.advanced.scenario.filter.domain;

public record FamilyScore(
        double structuralStrength,
        double confluenceStrength,
        double momentumAlignment,
        double ambiguityPenalty,
        double contradictionPenalty,
        double tradeUtility,
        double triggerReadiness,
        double finalRankScore
) {}
