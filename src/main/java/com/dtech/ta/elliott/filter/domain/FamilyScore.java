package com.dtech.ta.elliott.filter.domain;

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
