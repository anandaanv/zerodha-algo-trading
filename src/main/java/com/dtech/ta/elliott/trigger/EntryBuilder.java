package com.dtech.ta.elliott.trigger;

import com.dtech.ta.elliott.WaveHypothesis;
import com.dtech.ta.elliott.WaveScenario;
import com.dtech.ta.elliott.confluence.ConfluenceZone;
import com.dtech.ta.elliott.scenario.ScoredScenario;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EntryBuilder {

    private static final double ZONE_PROXIMITY_PCT = 0.01; // 1% proximity to zone

    public List<EntryCandidate> build(
            List<ScoredScenario> scoredScenarios,
            List<CandleTrigger> triggers,
            List<ConfluenceZone> decisionZones,
            double currentPrice) {

        List<EntryCandidate> candidates = new ArrayList<>();
        if (scoredScenarios == null || triggers == null || triggers.isEmpty()) return candidates;

        int idCounter = 1;

        for (ScoredScenario ss : scoredScenarios) {
            if (ss.getStatus() != ScenarioStatus.LEADING
                    && ss.getStatus() != ScenarioStatus.ACTIVE_ALTERNATE) continue;

            WaveScenario scenario = ss.getScenario();
            if (scenario.getHypotheses() == null || scenario.getHypotheses().isEmpty()) continue;

            // Use top-scoring hypothesis
            WaveHypothesis topHyp = scenario.getHypotheses().stream()
                    .max((a, b) -> Double.compare(a.getTotalScore(), b.getTotalScore()))
                    .orElse(null);
            if (topHyp == null) continue;

            // Check if current price is near a decision zone
            boolean nearZone = isNearDecisionZone(currentPrice, decisionZones);
            if (!nearZone) continue;

            // Find matching trigger (direction must match hypothesis)
            for (CandleTrigger trigger : triggers) {
                if (trigger.isBullish() != topHyp.isNextMajorMoveUp()) continue;

                double stopLoss  = topHyp.getInvalidationLevel();
                double target1   = topHyp.getPrimaryTarget() != null ? topHyp.getPrimaryTarget().getLevel() : 0.0;
                double target2   = getSecondTarget(topHyp);
                double rr        = computeRR(trigger.getTriggerPrice(), stopLoss, target1, topHyp.isNextMajorMoveUp());
                String style     = isAggressiveTrigger(trigger.getType()) ? "AGGRESSIVE" : "MODERATE";

                List<String> rationale = new ArrayList<>();
                rationale.add("trigger: " + trigger.getType());
                rationale.add("scenario: " + scenario.getId() + " " + scenario.getDirection());
                rationale.add("hypothesis: " + topHyp.getId() + " score=" + topHyp.getTotalScore());
                rationale.add("riskReward: " + String.format("%.2f", rr));

                candidates.add(EntryCandidate.builder()
                        .id("EC_" + idCounter++)
                        .scenarioId(scenario.getId())
                        .hypothesisId(topHyp.getId())
                        .bullish(topHyp.isNextMajorMoveUp())
                        .entryStyle(style)
                        .triggerType(trigger.getType())
                        .entryPrice(trigger.getTriggerPrice())
                        .stopLoss(stopLoss)
                        .target1(target1)
                        .target2(target2)
                        .riskRewardRatio(rr)
                        .rationale(rationale)
                        .build());
                break; // one entry candidate per scenario
            }
        }

        return candidates;
    }

    private boolean isNearDecisionZone(double price, List<ConfluenceZone> zones) {
        if (zones == null || zones.isEmpty()) return false;
        for (ConfluenceZone zone : zones) {
            double lo = zone.getLowerPrice() * (1 - ZONE_PROXIMITY_PCT);
            double hi = zone.getUpperPrice() * (1 + ZONE_PROXIMITY_PCT);
            if (price >= lo && price <= hi) return true;
        }
        return false;
    }

    private double getSecondTarget(WaveHypothesis hyp) {
        if (hyp.getTargetZones() != null && hyp.getTargetZones().size() >= 2) {
            return hyp.getTargetZones().get(1).getLevel();
        }
        return 0.0;
    }

    private double computeRR(double entry, double stop, double target, boolean bullish) {
        if (target == 0 || stop == 0) return 0.0;
        double risk   = Math.abs(entry - stop);
        double reward = Math.abs(target - entry);
        return risk > 0 ? reward / risk : 0.0;
    }

    private boolean isAggressiveTrigger(TriggerType type) {
        return type == TriggerType.HAMMER
                || type == TriggerType.SHOOTING_STAR
                || type == TriggerType.BULLISH_ENGULFING
                || type == TriggerType.BEARISH_ENGULFING;
    }
}
