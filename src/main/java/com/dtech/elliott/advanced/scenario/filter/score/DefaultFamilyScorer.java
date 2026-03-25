package com.dtech.elliott.advanced.scenario.filter.score;

import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class DefaultFamilyScorer implements FamilyScorer {

    private static final Logger log = LoggerFactory.getLogger(DefaultFamilyScorer.class);

    @Override
    public List<ScenarioFamilyCandidate> score(List<ScenarioFamilyCandidate> families, FilterConfig config) {
        if (families == null || families.isEmpty()) return List.of();
        return families.stream().map(f -> scoreFamily(f, config)).toList();
    }

    private ScenarioFamilyCandidate scoreFamily(ScenarioFamilyCandidate family, FilterConfig config) {
        List<ScenarioCluster> clusters = family.sourceClusters();

        double structuralStrength = clusters.isEmpty() ? 0.3
                : clusters.stream().mapToDouble(ScenarioCluster::aggregatedStructuralScore).average().orElse(0.3);

        double confluenceStrength = clusters.isEmpty() ? 0.3
                : clusters.stream().mapToDouble(ScenarioCluster::aggregatedConfluenceScore).average().orElse(0.3);

        double momentumAlignment = clusters.isEmpty() ? 0.3
                : clusters.stream().mapToDouble(ScenarioCluster::aggregatedMomentumScore).average().orElse(0.3);

        double ambiguityPenalty;
        if (family.familyType() == ScenarioFamilyType.NO_TRADE_NOISE) {
            ambiguityPenalty = 0.8;
        } else if (clusters.size() <= 1) {
            ambiguityPenalty = 0.1;
        } else {
            // std dev of structural scores / mean, clamped 0..1
            double mean = structuralStrength;
            double variance = clusters.stream()
                    .mapToDouble(c -> Math.pow(c.aggregatedStructuralScore() - mean, 2))
                    .average().orElse(0.0);
            ambiguityPenalty = mean > 0 ? Math.min(Math.sqrt(variance) / mean, 1.0) : 0.5;
        }

        double contradictionPenalty = Math.min(
                (family.contradictingPatterns() != null ? family.contradictingPatterns().size() : 0) * 0.1, 1.0);

        double tradeUtility = computeTradeUtility(family);

        double triggerReadiness;
        if (family.triggerEligible() && family.decisionZoneNearby()) {
            triggerReadiness = 0.8;
        } else if (family.triggerEligible()) {
            triggerReadiness = 0.5;
        } else if (family.decisionZoneNearby()) {
            triggerReadiness = 0.2;
        } else {
            triggerReadiness = 0.1;
        }

        double finalRankScore =
                config.structuralWeight()        * structuralStrength
              + config.confluenceWeight()         * confluenceStrength
              + config.momentumWeight()           * momentumAlignment
              + config.tradeUtilityWeight()       * tradeUtility
              + config.triggerReadinessWeight()   * triggerReadiness
              - config.ambiguityPenaltyWeight()   * ambiguityPenalty
              - config.contradictionPenaltyWeight() * contradictionPenalty;

        FamilyScore score = new FamilyScore(
                structuralStrength, confluenceStrength, momentumAlignment,
                ambiguityPenalty, contradictionPenalty, tradeUtility, triggerReadiness,
                finalRankScore
        );

        log.debug("Scored family {}: finalRankScore={}", family.id(), finalRankScore);

        return new ScenarioFamilyCandidate(
                family.id(), family.symbol(), family.anchorTimeframe(), family.familyType(),
                family.directionalBias(), family.sourceClusters(), family.supportingStructures(),
                family.supportingPatterns(), family.contradictingPatterns(),
                family.primaryInvalidationLevel(), family.confirmationLevel(),
                family.projectedTargetReference(), family.decisionZoneNearby(),
                family.triggerEligible(), family.tradableNow(), family.status(),
                score,
                family.explanation(), family.reasonCodes(), family.metadata()
        );
    }

    private double computeTradeUtility(ScenarioFamilyCandidate family) {
        double base = family.tradableNow() ? 0.8 : 0.3;
        if (family.decisionZoneNearby()) base += 0.1;
        if (family.triggerEligible()) base += 0.1;
        if (family.familyType() == ScenarioFamilyType.LOW_CONVICTION_REVERSAL_SEED
                || family.familyType() == ScenarioFamilyType.NO_TRADE_NOISE) {
            base -= 0.2;
        }
        return Math.max(0.0, Math.min(1.0, base));
    }
}
