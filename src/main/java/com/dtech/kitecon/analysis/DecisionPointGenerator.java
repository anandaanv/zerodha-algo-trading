package com.dtech.kitecon.analysis;

import com.dtech.ta.elliott.ElliottWaveAnalysis;
import com.dtech.ta.elliott.NestedWaveContext;
import com.dtech.ta.elliott.NestedWaveContext.BranchHypothesis;
import com.dtech.ta.elliott.PatternMatch;
import com.dtech.ta.elliott.PatternStatus;
import com.dtech.ta.elliott.GapLevel;
import com.dtech.kitecon.analysis.dto.NormalizedScenario;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DecisionPointGenerator {

    public String generate(
            ElliottWaveAnalysis analysis,
            List<PatternMatch> dedupedPatterns,
            List<NormalizedScenario> normalizedScenarios,
            double currentPrice,
            String entryTf,
            String symbol) {

        // Step 1: Find primary nested corrective context (prefers 1h > 15m > entryTf)
        NestedWaveContext nestedContext = findPrimaryNestedContext(analysis, entryTf);
        // Use the actual resolved TF in the header, not the raw entryTf parameter
        String resolvedTf = nestedContext != null && nestedContext.getTimeframe() != null
                ? nestedContext.getTimeframe() : entryTf;

        // Step 2: Extract key level
        Double keyLevel = null;
        String keyLevelMeaning = "N/A";
        if (nestedContext != null) {
            keyLevel = nestedContext.getInvalidationLevel();
            if (keyLevel != null) {
                keyLevelMeaning = deriveKeyLevelMeaning(nestedContext);
            }
        }

        // Step 3: Extract scenarios A and B
        ScenarioPair scenarioPair = extractScenarios(nestedContext, normalizedScenarios);
        ScenarioData scenarioA = scenarioPair.scenarioA;
        ScenarioData scenarioB = scenarioPair.scenarioB;

        // Step 4: WHERE WE ARE
        String whereWeAre = deriveWhereWeAre(nestedContext);

        // Step 5: WATCH FOR
        WatchForItems watchForItems = deriveWatchForItems(dedupedPatterns, analysis, currentPrice, entryTf, keyLevel);

        // Step 6: HIGHEST CONFIDENCE PATTERN
        String highestConfidencePattern = deriveHighestConfidencePattern(dedupedPatterns, entryTf);

        // Format output
        return formatOutput(
                symbol,
                currentPrice,
                resolvedTf,
                whereWeAre,
                keyLevel,
                keyLevelMeaning,
                scenarioA,
                scenarioB,
                watchForItems,
                highestConfidencePattern
        );
    }

    // ========== Step 1: Find primary nested corrective context ==========
    // Priority: exact entryTf match → "1h" → "15m" → last available
    private NestedWaveContext findPrimaryNestedContext(ElliottWaveAnalysis analysis, String entryTf) {
        if (analysis == null) return null;
        List<NestedWaveContext> contexts = analysis.getNestedCorrectiveContexts();
        if (contexts == null || contexts.isEmpty()) return null;

        for (String candidate : new String[]{entryTf, "1h", "15m"}) {
            if (candidate == null) continue;
            Optional<NestedWaveContext> match = contexts.stream()
                    .filter(ctx -> candidate.equals(ctx.getTimeframe()))
                    .findFirst();
            if (match.isPresent()) return match.get();
        }
        return contexts.get(contexts.size() - 1);
    }

    // ========== Step 2: Derive key level meaning ==========
    private String deriveKeyLevelMeaning(NestedWaveContext nestedContext) {
        if (nestedContext == null) {
            return "N/A";
        }

        List<BranchHypothesis> branches = nestedContext.getBranches();
        if (branches == null || branches.isEmpty()) {
            return "N/A";
        }

        // Look at higher-probability branch (last after sorting)
        BranchHypothesis higherProbBranch = branches.stream()
                .max(Comparator.comparingDouble(BranchHypothesis::getProbability))
                .orElse(null);

        if (higherProbBranch == null) {
            return "N/A";
        }

        String nextHigherDegreeMove = higherProbBranch.getNextHigherDegreeMove();
        if (nextHigherDegreeMove == null) {
            return "N/A";
        }

        String lower = nextHigherDegreeMove.toLowerCase();
        if (lower.contains("bear") || lower.contains("down") || lower.contains("4c") || lower.contains("decline")) {
            return "Hold above this level required for extension";
        } else {
            return "Break below this level starts correction immediately";
        }
    }

    // ========== Step 3: Extract scenarios A and B ==========
    private ScenarioPair extractScenarios(NestedWaveContext nestedContext, List<NormalizedScenario> normalizedScenarios) {
        List<BranchHypothesis> branches = null;

        if (nestedContext != null) {
            branches = nestedContext.getBranches();
        }

        if (branches != null && branches.size() >= 2) {
            // Sort by probability ascending
            List<BranchHypothesis> sorted = branches.stream()
                    .sorted(Comparator.comparingDouble(BranchHypothesis::getProbability))
                    .collect(Collectors.toList());

            ScenarioData scenarioA = branchToScenarioData(sorted.get(0));
            ScenarioData scenarioB = branchToScenarioData(sorted.get(sorted.size() - 1));

            return new ScenarioPair(scenarioA, scenarioB);
        }

        // Fall back to normalizedScenarios top 2
        if (normalizedScenarios != null && normalizedScenarios.size() >= 2) {
            ScenarioData scenarioA = normalizedScenarioToScenarioData(normalizedScenarios.get(0));
            ScenarioData scenarioB = normalizedScenarioToScenarioData(normalizedScenarios.get(1));
            return new ScenarioPair(scenarioA, scenarioB);
        }

        // Minimal fallback
        return new ScenarioPair(
                new ScenarioData("Unknown A", 25, "Watch for confirmation candle", null, "NEUTRAL"),
                new ScenarioData("Unknown B", 75, "Watch for confirmation candle", null, "NEUTRAL")
        );
    }

    private ScenarioData branchToScenarioData(BranchHypothesis branch) {
        String label = branch.getLabel();
        int probability = Math.round((float) branch.getProbability() * 100);
        String trigger = branch.getTrigger();
        if (trigger == null) {
            trigger = "Watch for confirmation candle";
        }
        Double targetLevel = branch.getTargetLevel();
        String bias = inferBias(branch.getNextHigherDegreeMove());

        return new ScenarioData(label, probability, trigger, targetLevel, bias);
    }

    private ScenarioData normalizedScenarioToScenarioData(NormalizedScenario scenario) {
        String label = scenario.getLabel() != null ? scenario.getLabel() : "Scenario";
        int probability = (int) scenario.getNormalizedPct();
        String trigger = "Watch for confirmation candle";
        Double targetLevel = scenario.getTarget();
        String bias = scenario.getBias() != null ? scenario.getBias() : "NEUTRAL";

        return new ScenarioData(label, probability, trigger, targetLevel, bias);
    }

    private String inferBias(String nextHigherDegreeMove) {
        if (nextHigherDegreeMove == null) {
            return "NEUTRAL";
        }

        String lower = nextHigherDegreeMove.toLowerCase();
        // Extension = temporary bull move before the next bearish wave
        if (lower.contains("extension") || lower.contains("extend") || lower.contains("bull")) {
            return "BULL_TEMPORARY";
        } else if (lower.contains("bear") || lower.contains("down") || lower.contains("correction")
                || lower.contains("4c") || lower.contains("truncat")) {
            return "BEAR";
        } else if (lower.contains("impulse") || lower.contains("wave 5") || lower.contains("w5")) {
            return "BULL";
        } else {
            return "NEUTRAL";
        }
    }

    // ========== Step 4: WHERE WE ARE ==========
    private String deriveWhereWeAre(NestedWaveContext nestedContext) {
        if (nestedContext == null) {
            return "Wave structure analysis in progress — monitoring key levels.";
        }

        String higherDegreeWave = nestedContext.getHigherDegreeWave();
        String correctiveLeg = nestedContext.getCorrectiveLeg();
        String activeSubwave = nestedContext.getActiveSubwave();
        String narrative = nestedContext.getNarrative();

        StringBuilder sb = new StringBuilder();
        if (higherDegreeWave != null) {
            sb.append(higherDegreeWave).append(" corrective structure, ");
        }
        if (correctiveLeg != null) {
            sb.append(correctiveLeg).append(" ");
        }
        if (activeSubwave != null) {
            sb.append(activeSubwave);
        }
        sb.append(" — ");

        if (narrative != null) {
            int endIdx = Math.min(80, narrative.length());
            int periodIdx = narrative.indexOf('.', 0);
            if (periodIdx > 0 && periodIdx < endIdx) {
                endIdx = periodIdx;
            }
            sb.append(narrative, 0, endIdx);
        } else {
            sb.append("Wave structure developing");
        }

        return sb.toString();
    }

    // ========== Step 5: WATCH FOR ==========
    private WatchForItems deriveWatchForItems(
            List<PatternMatch> dedupedPatterns,
            ElliottWaveAnalysis analysis,
            double currentPrice,
            String entryTf,
            Double keyLevel) {

        String watchItem1 = null;
        String watchItem2 = null;

        // Watch Item 1: From dedupedPatterns
        if (dedupedPatterns != null && !dedupedPatterns.isEmpty()) {
            Optional<PatternMatch> patternMatch = dedupedPatterns.stream()
                    .filter(p -> entryTf.equals(p.getTimeframe()) || "15m".equals(p.getTimeframe()))
                    .filter(p -> p.getResistance() != null || p.getSupport() != null)
                    .filter(p -> {
                        Double resistance = p.getResistance();
                        Double support = p.getSupport();
                        if (resistance != null && Math.abs(resistance - currentPrice) / currentPrice <= 0.05) {
                            return true;
                        }
                        if (support != null && Math.abs(support - currentPrice) / currentPrice <= 0.05) {
                            return true;
                        }
                        return false;
                    })
                    .max(Comparator.comparingDouble(PatternMatch::getConfidence));

            if (patternMatch.isPresent()) {
                PatternMatch pm = patternMatch.get();
                Double resistance = pm.getResistance();
                Double support = pm.getSupport();

                if (resistance != null && support != null) {
                    watchItem1 = String.format("Pattern breakout: above %.2f or below %.2f", resistance, support);
                } else if (resistance != null) {
                    watchItem1 = String.format("Pattern breakout: above %.2f", resistance);
                } else if (support != null) {
                    watchItem1 = String.format("Pattern breakout: below %.2f", support);
                }
            }
        }

        // Watch Item 2: From gaps or key level
        if (analysis != null && analysis.getSignificantGaps() != null && !analysis.getSignificantGaps().isEmpty()) {
            Optional<GapLevel> nearestGap = analysis.getSignificantGaps().stream()
                    .filter(g -> {
                        double midpoint = (g.getGapBottom() + g.getGapTop()) / 2;
                        return Math.abs(midpoint - currentPrice) / currentPrice <= 0.10;
                    })
                    .min(Comparator.comparingDouble(g -> Math.abs(((g.getGapBottom() + g.getGapTop()) / 2) - currentPrice)));

            if (nearestGap.isPresent()) {
                GapLevel gap = nearestGap.get();
                String resistance_or_support = (gap.getGapBottom() + gap.getGapTop()) / 2 > currentPrice ? "RESISTANCE" : "SUPPORT";
                watchItem2 = String.format("Gap zone %.2f–%.2f acts as %s", gap.getGapBottom(), gap.getGapTop(), resistance_or_support);
            }
        }

        // Fallback for watch item 2
        if (watchItem2 == null && keyLevel != null) {
            watchItem2 = String.format("Monitor %.2f as invalidation anchor", keyLevel);
        }

        if (watchItem2 == null) {
            watchItem2 = "Monitor price action near key support/resistance";
        }

        if (watchItem1 == null) {
            watchItem1 = "Monitor entry confirmation signals";
        }

        return new WatchForItems(watchItem1, watchItem2);
    }

    // ========== Step 6: HIGHEST CONFIDENCE PATTERN ==========
    private String deriveHighestConfidencePattern(List<PatternMatch> dedupedPatterns, String entryTf) {
        if (dedupedPatterns == null || dedupedPatterns.isEmpty()) {
            return "No WATCHING patterns on entry timeframe";
        }

        Optional<PatternMatch> highestConfidence = dedupedPatterns.stream()
                .filter(p -> entryTf.equals(p.getTimeframe()) || "15m".equals(p.getTimeframe()))
                .filter(p -> PatternStatus.WATCHING.equals(p.getStatus()) || PatternStatus.BUILDING.equals(p.getStatus()))
                .max(Comparator.comparingDouble(PatternMatch::getConfidence));

        if (highestConfidence.isPresent()) {
            PatternMatch pm = highestConfidence.get();
            String tf = pm.getTimeframe();
            String type = pm.getType() != null ? pm.getType().toString() : "UNKNOWN";
            String status = pm.getStatus().toString();
            double confidence = pm.getConfidence();
            String description = pm.getDescription();

            String confStr = String.format("%.0f", confidence);
            StringBuilder result = new StringBuilder(String.format("%s %s %s conf=%s", tf, type, status, confStr));
            if (description != null && !description.isEmpty()) {
                result.append("\n  ").append(description);
            }
            return result.toString();
        }

        return "No WATCHING patterns on entry timeframe";
    }

    // ========== Format output ==========
    private String formatOutput(
            String symbol,
            double currentPrice,
            String entryTf,
            String whereWeAre,
            Double keyLevel,
            String keyLevelMeaning,
            ScenarioData scenarioA,
            ScenarioData scenarioB,
            WatchForItems watchForItems,
            String highestConfidencePattern) {

        String keyLevelStr = keyLevel != null ? String.format("%.2f", keyLevel) : "N/A";
        String priceStr = String.format("%.2f", currentPrice);
        String targetAStr = scenarioA.targetLevel != null ? String.format("%.2f", scenarioA.targetLevel) : "not specified";
        String targetBStr = scenarioB.targetLevel != null ? String.format("%.2f", scenarioB.targetLevel) : "not specified";

        return String.format(
                "=== CURRENT DECISION POINT ===\n\n" +
                "Symbol:       %s\n" +
                "Price:        %s\n" +
                "Timeframe:    %s\n" +
                "Generated:    %s\n\n" +
                "WHERE WE ARE:\n" +
                "  %s\n\n" +
                "KEY LEVEL:    %s\n" +
                "  Meaning:    %s\n\n" +
                "NEXT MOVE — TWO SCENARIOS:\n" +
                "  Scenario A (%d%%):\n" +
                "    Label:    %s\n" +
                "    Trigger:  %s\n" +
                "    Target:   %s\n" +
                "    Bias:     %s\n\n" +
                "  Scenario B (%d%%):\n" +
                "    Label:    %s\n" +
                "    Trigger:  %s\n" +
                "    Target:   %s\n" +
                "    Bias:     %s\n\n" +
                "WATCH FOR:\n" +
                "  - %s\n" +
                "  - %s\n\n" +
                "HIGHEST CONFIDENCE PATTERN:\n" +
                "  %s",
                symbol,
                priceStr,
                entryTf,
                Instant.now().toString(),
                whereWeAre,
                keyLevelStr,
                keyLevelMeaning,
                scenarioA.probability,
                scenarioA.label,
                scenarioA.trigger,
                targetAStr,
                scenarioA.bias,
                scenarioB.probability,
                scenarioB.label,
                scenarioB.trigger,
                targetBStr,
                scenarioB.bias,
                watchForItems.item1,
                watchForItems.item2,
                highestConfidencePattern
        );
    }

    // ========== Helper records/classes ==========
    private record ScenarioPair(ScenarioData scenarioA, ScenarioData scenarioB) {}

    private record ScenarioData(String label, int probability, String trigger, Double targetLevel, String bias) {}

    private record WatchForItems(String item1, String item2) {}
}
