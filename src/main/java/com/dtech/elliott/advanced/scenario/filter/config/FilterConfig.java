package com.dtech.elliott.advanced.scenario.filter.config;

public record FilterConfig(
        double hardPruneStructuralScoreFloor,
        double weakPruneStructuralScoreFloor,
        double ambiguityCeilingForLeadingScenario,
        double contradictionPenaltyWeight,
        double ambiguityPenaltyWeight,
        double structuralWeight,
        double confluenceWeight,
        double momentumWeight,
        double tradeUtilityWeight,
        double triggerReadinessWeight,
        double invalidationBucketSize,
        int maxMembersPerCluster,
        int maxFamiliesBeforeCompression,
        int maxActiveAlternates,
        int maxWeakAlternates,
        boolean keepContrarianAlternate,
        boolean suppressNoTradeNoiseFamilies
) {
    public static FilterConfig defaults() {
        return new FilterConfig(
                0.20,  // hardPruneStructuralScoreFloor
                0.35,  // weakPruneStructuralScoreFloor
                0.60,  // ambiguityCeilingForLeadingScenario
                0.30,  // contradictionPenaltyWeight
                0.25,  // ambiguityPenaltyWeight
                0.35,  // structuralWeight
                0.25,  // confluenceWeight
                0.15,  // momentumWeight
                0.15,  // tradeUtilityWeight
                0.10,  // triggerReadinessWeight
                50.0,  // invalidationBucketSize
                10,    // maxMembersPerCluster
                8,     // maxFamiliesBeforeCompression
                2,     // maxActiveAlternates
                1,     // maxWeakAlternates
                true,  // keepContrarianAlternate
                true   // suppressNoTradeNoiseFamilies
        );
    }
}
