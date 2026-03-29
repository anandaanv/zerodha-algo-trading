package com.dtech.kitecon.analysis;

import com.dtech.ta.elliott.ElliottWaveAnalysis;
import com.dtech.ta.elliott.PatternMatch;
import com.dtech.ta.elliott.PatternStatus;
import com.dtech.ta.elliott.PatternType;
import com.dtech.ta.elliott.NestedWaveContext;
import com.dtech.ta.elliott.NestedWaveContext.BranchHypothesis;
import com.dtech.ta.elliott.GapLevel;
import com.dtech.ta.elliott.WaveScenario;
import com.dtech.ta.elliott.WaveContextHint;
import com.dtech.ta.elliott.WaveLabel;
import com.dtech.kitecon.analysis.dto.NormalizedScenario;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class OutputRestructurer {

    public String restructure(
            ElliottWaveAnalysis analysis,
            List<PatternMatch> dedupedPatterns,
            List<NormalizedScenario> normalizedScenarios,
            String decisionPointText,
            String symbol,
            double currentPrice) {

        StringBuilder sb = new StringBuilder();

        // Section 1: Decision Point
        sb.append(decisionPointText).append("\n\n");

        // Section 2: Nested Corrective Context
        sb.append(buildNestedCorrectiveContext(analysis));

        // Section 3: Competing Scenarios
        sb.append(buildCompetingScenarios(normalizedScenarios));

        // Section 4: Patterns (deduplicated)
        sb.append(buildPatternsSection(dedupedPatterns));

        // Section 5: Gap Confluence
        sb.append(buildGapConfluence(analysis, currentPrice));

        // Section 6: Building Patterns Summary
        sb.append(buildBuildingPatternsSummary(analysis));

        return sb.toString();
    }

    private String buildNestedCorrectiveContext(ElliottWaveAnalysis analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== NESTED CORRECTIVE CONTEXT ===\n\n");

        List<NestedWaveContext> contexts = analysis.getNestedCorrectiveContexts();
        if (contexts == null || contexts.isEmpty()) {
            sb.append("(none)\n");
            return sb.toString();
        }

        for (NestedWaveContext ctx : contexts) {
            String tf = ctx.getTimeframe() != null ? ctx.getTimeframe() : "?";
            String higherDegree = ctx.getHigherDegreeWave() != null ? ctx.getHigherDegreeWave() : "?";
            String correctiveLeg = ctx.getCorrectiveLeg() != null ? ctx.getCorrectiveLeg() : "?";
            String activeSubwave = ctx.getActiveSubwave() != null ? ctx.getActiveSubwave() : "?";
            String dominantStructure = ctx.getDominantStructure() != null ? ctx.getDominantStructure() : "?";

            sb.append("[").append(tf).append("] ")
                    .append(higherDegree).append(" → ")
                    .append(correctiveLeg).append(" → ")
                    .append(activeSubwave).append(" | ")
                    .append(dominantStructure).append("\n");

            String narrative = ctx.getNarrative();
            if (narrative != null && !narrative.isBlank()) {
                sb.append("  ").append(narrative).append("\n");
            }

            List<BranchHypothesis> branches = ctx.getBranches();
            if (branches != null && !branches.isEmpty()) {
                sb.append("  Branches:\n");
                for (BranchHypothesis branch : branches) {
                    String code = branch.getCode() != null ? branch.getCode() : "?";
                    double prob = branch.getProbability();
                    Double target = branch.getTargetLevel();
                    String label = branch.getLabel() != null ? branch.getLabel() : "";

                    sb.append("    [").append(code).append("] prob=")
                            .append(String.format("%.0f", prob * 100)).append("% target=")
                            .append(target != null ? fmt(target) : "?").append(" — ").append(label).append("\n");

                    String trigger = branch.getTrigger();
                    if (trigger != null && !trigger.isBlank()) {
                        sb.append("      Trigger: ").append(trigger).append("\n");
                    }

                    String nextMove = branch.getNextHigherDegreeMove();
                    if (nextMove != null && !nextMove.isBlank()) {
                        sb.append("      Next: ").append(nextMove).append("\n");
                    }
                }
            }

            Double invalidationLevel = ctx.getInvalidationLevel();
            if (invalidationLevel != null) {
                sb.append("  Invalidation anchor: ").append(fmt(invalidationLevel)).append("\n");
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildCompetingScenarios(List<NormalizedScenario> normalizedScenarios) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== COMPETING SCENARIOS ===\n\n");

        if (normalizedScenarios == null || normalizedScenarios.isEmpty()) {
            sb.append("(none)\n");
            return sb.toString();
        }

        for (NormalizedScenario scenario : normalizedScenarios) {
            String id = scenario.getId() != null ? scenario.getId() : "?";
            String label = scenario.getLabel() != null ? scenario.getLabel() : "";
            double rawScore = scenario.getRawScore();
            double normalizedPct = scenario.getNormalizedPct();
            boolean isLeading = scenario.isLeading();

            sb.append("[").append(id).append("] ").append(label)
                    .append(" — score=").append(fmt(rawScore))
                    .append(" normalized=").append(String.format("%.0f", normalizedPct)).append("%");

            if (isLeading) {
                sb.append(" ← LEADING");
            }
            sb.append("\n");

            String bias = scenario.getBias();
            if (bias != null && !bias.isBlank()) {
                sb.append("  Bias:         ").append(bias).append("\n");
            }

            Double target = scenario.getTarget();
            if (target != null) {
                sb.append("  Target:       ").append(fmt(target)).append("\n");
            } else {
                sb.append("  Target:       varies\n");
            }

            double scenarioInvalidation = scenario.getScenarioInvalidation();
            if (!Double.isNaN(scenarioInvalidation)) {
                sb.append("  Invalidation: ").append(String.format("%.2f", scenarioInvalidation)).append("\n");
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildPatternsSection(List<PatternMatch> dedupedPatterns) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PATTERNS (deduplicated) ===\n\n");

        if (dedupedPatterns == null || dedupedPatterns.isEmpty()) {
            sb.append("(none)\n");
            return sb.toString();
        }

        // Group by timeframe
        Map<String, List<PatternMatch>> byTimeframe = dedupedPatterns.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getTimeframe() != null ? p.getTimeframe() : "?",
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        for (Map.Entry<String, List<PatternMatch>> entry : byTimeframe.entrySet()) {
            String tf = entry.getKey();
            List<PatternMatch> patterns = entry.getValue();

            // Sort by confidence descending
            patterns.sort((a, b) -> Double.compare(b.getConfidence(), a.getConfidence()));

            for (PatternMatch pattern : patterns) {
                PatternType type = pattern.getType();
                String typeStr = type != null ? type.name() : "?";
                PatternStatus status = pattern.getStatus();
                String statusStr = status != null ? status.name() : "?";
                double confidence = pattern.getConfidence();

                sb.append("[").append(tf).append("] ").append(typeStr)
                        .append(" [").append(statusStr).append("] conf=")
                        .append(String.format("%.0f", confidence)).append("\n");

                Double resistance = pattern.getResistance();
                Double support = pattern.getSupport();
                String resistanceStr = resistance != null ? fmt(resistance) : "?";
                String supportStr = support != null ? fmt(support) : "?";

                sb.append("  Highs: ").append(resistanceStr).append(" | Lows: ").append(supportStr).append("\n");

                // Elliott wave context
                List<WaveContextHint> hints = pattern.getWaveContextHints();
                if (hints != null && !hints.isEmpty()) {
                    WaveContextHint waveHint = hints.get(0);
                    WaveLabel impliedPosition = waveHint.getImpliedCurrentPosition();
                    WaveLabel impliedNextWave = waveHint.getImpliedNextWave();
                    double prob = waveHint.getProbability();

                    if (impliedPosition != null && impliedNextWave != null) {
                        sb.append("  Elliott: ").append(impliedPosition).append(" → ")
                                .append(impliedNextWave).append(" (prob=")
                                .append(String.format("%.2f", prob)).append(")\n");
                    } else {
                        sb.append("  Elliott: (no wave context)\n");
                    }
                } else {
                    sb.append("  Elliott: (no wave context)\n");
                }

                // Status-specific details
                if (status == PatternStatus.WATCHING) {
                    Double res = pattern.getResistance();
                    Double sup = pattern.getSupport();
                    String statusText = "Status: Breakout above " + (res != null ? fmt(res) : "?")
                            + " or below " + (sup != null ? fmt(sup) : "?");
                    sb.append("  ").append(statusText).append("\n");
                } else if (status == PatternStatus.BUILDING) {
                    String waitingFor = pattern.getWaitingFor();
                    if (waitingFor != null && !waitingFor.isBlank()) {
                        sb.append("  Status: ").append(waitingFor).append("\n");
                    }
                }

                String description = pattern.getDescription();
                if (description != null && !description.isBlank()) {
                    sb.append("  ").append(description).append("\n");
                }

                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String buildGapConfluence(ElliottWaveAnalysis analysis, double currentPrice) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== GAP CONFLUENCE ===\n\n");

        Map<String, List<GapLevel>> gapsByTf = analysis.getGapsByTf();
        if (gapsByTf == null || gapsByTf.isEmpty()) {
            sb.append("(none)\n");
            return sb.toString();
        }

        boolean hasAnyGaps = false;
        for (Map.Entry<String, List<GapLevel>> entry : gapsByTf.entrySet()) {
            String tf = entry.getKey();
            List<GapLevel> gaps = entry.getValue();

            for (GapLevel gap : gaps) {
                // Skip MINOR gaps
                if (gap.getSignificance() == GapLevel.GapSignificance.MINOR) {
                    continue;
                }
                hasAnyGaps = true;

                String direction = gap.getDirection() != null ? gap.getDirection().toString() : "?";
                double gapBottom = gap.getGapBottom();
                double gapTop = gap.getGapTop();
                String significance = gap.getSignificance() != null ? gap.getSignificance().toString() : "?";
                String fillStatus = gap.getFillStatus() != null ? gap.getFillStatus().toString() : "";

                boolean nearCurrent = Math.abs(currentPrice - gapBottom) < 0.01 ||
                        Math.abs(currentPrice - gapTop) < 0.01;

                sb.append("  [").append(tf).append("] ").append(direction)
                        .append(" ").append(fmt(gapBottom)).append("–")
                        .append(fmt(gapTop)).append(" ").append(significance)
                        .append(" ").append(fillStatus);

                if (nearCurrent) {
                    sb.append(" ★NEAR");
                }
                sb.append("\n");
            }
        }

        if (!hasAnyGaps) {
            sb.append("(none)\n");
        }

        return sb.toString();
    }

    private String buildBuildingPatternsSummary(ElliottWaveAnalysis analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== BUILDING PATTERNS SUMMARY ===\n\n");

        List<PatternMatch> allPatterns = analysis.getAllPatterns();
        if (allPatterns == null || allPatterns.isEmpty()) {
            sb.append("(none)\n");
            sb.append("\nNOTE: Full building pattern list available on request.\n");
            sb.append("      Highest confidence patterns shown in PATTERNS section above.\n");
            return sb.toString();
        }

        // Group by timeframe
        Map<String, List<PatternMatch>> byTimeframe = allPatterns.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getTimeframe() != null ? p.getTimeframe() : "?",
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        for (Map.Entry<String, List<PatternMatch>> entry : byTimeframe.entrySet()) {
            String tf = entry.getKey();
            List<PatternMatch> patterns = entry.getValue();

            // Count patterns by type
            long doubleBottomCount = patterns.stream()
                    .filter(p -> p.getType() == PatternType.DOUBLE_BOTTOM).count();
            long doubleTopCount = patterns.stream()
                    .filter(p -> p.getType() == PatternType.DOUBLE_TOP).count();

            long trianglesCount = patterns.stream()
                    .filter(p -> p.getType() == PatternType.SYMMETRICAL_TRIANGLE ||
                            p.getType() == PatternType.ASCENDING_TRIANGLE ||
                            p.getType() == PatternType.DESCENDING_TRIANGLE)
                    .count();

            long wedgesCount = patterns.stream()
                    .filter(p -> p.getType() == PatternType.RISING_WEDGE ||
                            p.getType() == PatternType.FALLING_WEDGE)
                    .count();

            long channelsCount = patterns.stream()
                    .filter(p -> p.getType() == PatternType.ASCENDING_CHANNEL ||
                            p.getType() == PatternType.DESCENDING_CHANNEL ||
                            p.getType() == PatternType.HORIZONTAL_CHANNEL)
                    .count();

            long flagsCount = patterns.stream()
                    .filter(p -> p.getType() == PatternType.BULL_FLAG ||
                            p.getType() == PatternType.BEAR_FLAG ||
                            p.getType() == PatternType.BULL_PENNANT ||
                            p.getType() == PatternType.BEAR_PENNANT)
                    .count();

            // Output summary for this timeframe
            sb.append("[").append(tf).append("] ");
            List<String> lines = new ArrayList<>();

            if (doubleBottomCount > 0) {
                lines.add("Double Bottom: " + doubleBottomCount + " building");
            }
            if (doubleTopCount > 0) {
                lines.add("Double Top: " + doubleTopCount + " building");
            }

            sb.append(String.join(" | ", lines)).append("\n");

            lines.clear();
            if (trianglesCount > 0) {
                lines.add("Triangles: " + trianglesCount + " building/watching");
            }
            if (wedgesCount > 0) {
                lines.add("Wedges: " + wedgesCount + " building");
            }

            if (!lines.isEmpty()) {
                sb.append("     ").append(String.join(" | ", lines)).append("\n");
            }

            lines.clear();
            if (channelsCount > 0) {
                lines.add("Channels: " + channelsCount + " watching");
            }
            if (flagsCount > 0) {
                lines.add("Flags: " + flagsCount + " watching");
            }

            if (!lines.isEmpty()) {
                sb.append("     ").append(String.join(" | ", lines)).append("\n");
            }
        }

        sb.append("\nNOTE: Full building pattern list available on request.\n");
        sb.append("      Highest confidence patterns shown in PATTERNS section above.\n");

        return sb.toString();
    }

    private String fmt(double v) {
        return String.format("%.2f", v);
    }
}
