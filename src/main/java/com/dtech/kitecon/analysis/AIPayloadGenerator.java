package com.dtech.kitecon.analysis;

import com.dtech.ta.elliott.ElliottWaveAnalysis;
import com.dtech.ta.elliott.EnrichedPivot;
import com.dtech.ta.elliott.GapLevel;
import com.dtech.ta.elliott.NestedWaveContext;
import com.dtech.ta.elliott.NestedWaveContext.BranchHypothesis;
import com.dtech.ta.elliott.PatternMatch;
import com.dtech.ta.elliott.PatternStatus;
import com.dtech.ta.elliott.TfContext;
import com.dtech.ta.elliott.WaveContextHint;
import com.dtech.kitecon.analysis.dto.NormalizedScenario;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.dtech.ta.elliott.WaveCount;
import com.dtech.ta.elliott.WaveLabel;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIPayloadGenerator {

    private final ObjectMapper objectMapper;

    /**
     * Generates the JSON AI payload as a Map<String, Object> for AI analysis.
     *
     * @param analysis          ElliottWaveAnalysis with nested contexts and gap zones
     * @param dedupedPatterns   Deduplicated patterns from engine
     * @param normalizedScenarios Competing scenarios scored by engine
     * @param decisionPointText  Summary text from NestedWaveContext
     * @param currentPrice      Current price of the instrument
     * @param entryTf           Entry timeframe (e.g., "1h", "15m")
     * @param symbol            Trading symbol
     * @return Map<String, Object> representing the AI payload
     */
    public Map<String, Object> generate(
            ElliottWaveAnalysis analysis,
            List<PatternMatch> dedupedPatterns,
            List<NormalizedScenario> normalizedScenarios,
            String decisionPointText,
            double currentPrice,
            String entryTf,
            String symbol) {

        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("symbol", symbol);
        payload.put("current_price", currentPrice);
        payload.put("generated_at", Instant.now().toString());
        payload.put("entry_timeframe", entryTf);

        payload.put("decision_point", buildDecisionPointBlock(analysis, entryTf));
        payload.put("nested_context", buildNestedContextBlock(analysis, entryTf));
        payload.put("top_patterns", buildTopPatternsBlock(dedupedPatterns, entryTf, currentPrice));
        payload.put("competing_scenarios", buildCompetingScenariosBlock(normalizedScenarios));
        payload.put("gap_zones", buildGapZonesBlock(analysis, currentPrice));
        payload.put("cross_timeframe_summary", buildCrossTimeframeSummary(analysis));
        payload.put("pattern_counts", buildPatternCounts(analysis));
        payload.put("indicator_snapshot", buildIndicatorSnapshotBlock(analysis.getEnrichedPivots()));
        payload.put("wave_key_points", buildWaveKeyPointsBlock(analysis.getWaveCounts()));

        // Token budget enforcement
        int tokenEstimate = estimateTokens(payload);
        if (tokenEstimate > 2500) {
            log.info("Token estimate {} exceeds 2500, applying reductions", tokenEstimate);

            // First reduction: trim indicator_snapshot to last 1 pivot per TF
            Map<String, Object> indicatorSnapshot = (Map<String, Object>) payload.get("indicator_snapshot");
            if (indicatorSnapshot != null) {
                Map<String, Object> trimmed = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : indicatorSnapshot.entrySet()) {
                    Object val = e.getValue();
                    if (val instanceof List<?> list && list.size() > 1) {
                        trimmed.put(e.getKey(), List.of(list.get(list.size() - 1)));
                    } else {
                        trimmed.put(e.getKey(), val);
                    }
                }
                payload.put("indicator_snapshot", trimmed);
                tokenEstimate = estimateTokens(payload);
            }

            // Third reduction: keep only top 3 patterns
            List<Map<String, Object>> topPatterns =
                (List<Map<String, Object>>) payload.get("top_patterns");
            if (topPatterns.size() > 3) {
                payload.put("top_patterns", topPatterns.stream().limit(3).collect(Collectors.toList()));
                tokenEstimate = estimateTokens(payload);
            }

            // Fourth reduction: remove pattern_counts
            if (tokenEstimate > 2500) {
                payload.remove("pattern_counts");
                tokenEstimate = estimateTokens(payload);
                log.info("Removed pattern_counts, new token estimate: {}", tokenEstimate);
            }

            // Fifth reduction: keep only leading scenario
            if (tokenEstimate > 2500) {
                List<Map<String, Object>> scenarios =
                    (List<Map<String, Object>>) payload.get("competing_scenarios");
                if (scenarios != null && !scenarios.isEmpty()) {
                    List<Map<String, Object>> leadingOnly =
                        scenarios.stream()
                            .filter(s -> (boolean) s.getOrDefault("is_leading", false))
                            .limit(1)
                            .collect(Collectors.toList());
                    if (leadingOnly.isEmpty() && !scenarios.isEmpty()) {
                        leadingOnly.add(scenarios.get(0));
                    }
                    payload.put("competing_scenarios", leadingOnly);
                    tokenEstimate = estimateTokens(payload);
                    log.info("Kept only leading scenario, new token estimate: {}", tokenEstimate);
                }
            }
        }

        payload.put("_token_estimate", tokenEstimate);
        return payload;
    }

    private Map<String, Object> buildDecisionPointBlock(
            ElliottWaveAnalysis analysis, String entryTf) {

        Map<String, Object> block = new LinkedHashMap<>();

        // Find nested context matching entryTf or use last
        NestedWaveContext context = analysis.getNestedCorrectiveContexts().stream()
            .filter(c -> entryTf.equals(c.getTimeframe()))
            .findFirst()
            .orElse(analysis.getNestedCorrectiveContexts().isEmpty() ? null
                : analysis.getNestedCorrectiveContexts().get(
                    analysis.getNestedCorrectiveContexts().size() - 1));

        if (context == null) {
            return block;
        }

        // Extract "where_we_are" from narrative
        String wherWeAre = extractFirstSentence(context.getNarrative(), 100);
        block.put("where_we_are", wherWeAre);

        double keyLevel = context.getInvalidationLevel() != null ? context.getInvalidationLevel() : 0.0;
        block.put("key_level", keyLevel);
        block.put("key_level_meaning", "Hold above key level for extension");

        // Determine scenario_a (lower probability) and scenario_b (higher probability)
        List<BranchHypothesis> branches = context.getBranches();
        if (branches != null && branches.size() >= 2) {
            List<BranchHypothesis> sortedByProb = branches.stream()
                .sorted((b1, b2) -> Double.compare(b1.getProbability(), b2.getProbability()))
                .collect(Collectors.toList());

            BranchHypothesis branchA = sortedByProb.get(0);
            BranchHypothesis branchB = sortedByProb.get(sortedByProb.size() - 1);

            block.put("scenario_a", buildBranchScenario(branchA));
            block.put("scenario_b", buildBranchScenario(branchB));
        }

        block.put("watch_for", List.of("Wave invalidation", "Confluence zone breach", "Gap fill"));

        return block;
    }

    private Map<String, Object> buildBranchScenario(BranchHypothesis branch) {
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("label", branch.getLabel() != null ? branch.getLabel() : branch.getCode());
        scenario.put("probability", (int) (branch.getProbability() * 100));
        scenario.put("trigger", branch.getTrigger() != null ? branch.getTrigger() : "N/A");
        scenario.put("target", branch.getTargetLevel() != null ? branch.getTargetLevel() : 0.0);
        scenario.put("bias", "NEUTRAL");
        return scenario;
    }

    private Map<String, Object> buildNestedContextBlock(
            ElliottWaveAnalysis analysis, String entryTf) {

        Map<String, Object> block = new LinkedHashMap<>();

        NestedWaveContext context = analysis.getNestedCorrectiveContexts().stream()
            .filter(c -> entryTf.equals(c.getTimeframe()))
            .findFirst()
            .orElse(analysis.getNestedCorrectiveContexts().isEmpty() ? null
                : analysis.getNestedCorrectiveContexts().get(
                    analysis.getNestedCorrectiveContexts().size() - 1));

        if (context == null) {
            return block;
        }

        block.put("timeframe", context.getTimeframe());

        String wavePosition = (context.getHigherDegreeWave() != null ? context.getHigherDegreeWave() : "?")
            + " → "
            + (context.getCorrectiveLeg() != null ? context.getCorrectiveLeg() : "?");
        block.put("wave_position", wavePosition);

        String thesis = truncateText(context.getNarrative(), 200);
        block.put("thesis", thesis);

        // Build branches list
        List<Map<String, Object>> branchesList = new ArrayList<>();
        if (context.getBranches() != null) {
            for (BranchHypothesis branch : context.getBranches()) {
                Map<String, Object> branchMap = new LinkedHashMap<>();
                branchMap.put("id", branch.getCode());
                branchMap.put("label", branch.getLabel() != null ? branch.getLabel() : branch.getCode());
                branchMap.put("probability", (int) (branch.getProbability() * 100));
                branchMap.put("target", branch.getTargetLevel() != null ? branch.getTargetLevel() : 0.0);
                branchMap.put("trigger", branch.getTrigger() != null ? branch.getTrigger() : "N/A");
                branchMap.put("next_wave", branch.getNextHigherDegreeMove() != null ? branch.getNextHigherDegreeMove() : "?");
                branchesList.add(branchMap);
            }
        }
        block.put("branches", branchesList);

        double invalidationAnchor = context.getInvalidationLevel() != null ? context.getInvalidationLevel() : 0.0;
        block.put("invalidation_anchor", invalidationAnchor);

        return block;
    }

    private List<Map<String, Object>> buildTopPatternsBlock(
            List<PatternMatch> dedupedPatterns, String entryTf, double currentPrice) {

        List<Map<String, Object>> topPatterns = new ArrayList<>();

        if (dedupedPatterns == null || dedupedPatterns.isEmpty()) {
            return topPatterns;
        }

        List<PatternMatch> filtered = dedupedPatterns.stream()
            .filter(p -> entryTf.equals(p.getTimeframe()) || "15m".equals(p.getTimeframe()))
            .sorted((p1, p2) -> Double.compare(p2.getConfidence(), p1.getConfidence()))
            .limit(5)
            .collect(Collectors.toList());

        for (PatternMatch p : filtered) {
            Map<String, Object> patternMap = new LinkedHashMap<>();
            patternMap.put("timeframe", p.getTimeframe());
            patternMap.put("type", p.getType() != null ? p.getType().toString() : "UNKNOWN");
            patternMap.put("state", p.getStatus() != null ? p.getStatus().toString() : "UNKNOWN");
            patternMap.put("confidence", (int) p.getConfidence());

            Map<String, Object> keyLevels = new LinkedHashMap<>();
            keyLevels.put("resistance", p.getResistance() != null ? p.getResistance() : 0.0);
            keyLevels.put("support", p.getSupport() != null ? p.getSupport() : 0.0);
            patternMap.put("key_levels", keyLevels);

            // Elliott context from first hint
            String elliottContext = "N/A";
            double elliottProb = 0.0;
            if (p.getWaveContextHints() != null && !p.getWaveContextHints().isEmpty()) {
                WaveContextHint firstHint = p.getWaveContextHints().get(0);
                if (firstHint.getImpliedCurrentPosition() != null && firstHint.getImpliedNextWave() != null) {
                    elliottContext = firstHint.getImpliedCurrentPosition() + "→" + firstHint.getImpliedNextWave();
                }
                elliottProb = firstHint.getProbability();
            }
            patternMap.put("elliott_context", elliottContext);
            patternMap.put("elliott_probability", elliottProb);

            String breakoutDir = p.getStatus() == PatternStatus.WATCHING ? "PENDING" : "N/A";
            patternMap.put("breakout_direction", breakoutDir);

            topPatterns.add(patternMap);
        }

        return topPatterns;
    }

    private List<Map<String, Object>> buildCompetingScenariosBlock(
            List<NormalizedScenario> normalizedScenarios) {

        List<Map<String, Object>> scenarios = new ArrayList<>();

        if (normalizedScenarios == null || normalizedScenarios.isEmpty()) {
            return scenarios;
        }

        for (NormalizedScenario ns : normalizedScenarios) {
            Map<String, Object> scenario = new LinkedHashMap<>();
            scenario.put("id", ns.getId());
            scenario.put("label", ns.getLabel());
            scenario.put("score", (int) ns.getNormalizedPct());
            scenario.put("bias", ns.getBias());
            scenario.put("target", ns.getTarget() != null ? ns.getTarget() : null);
            scenario.put("invalidation", ns.getScenarioInvalidation());
            scenario.put("is_leading", ns.isLeading());
            scenarios.add(scenario);
        }

        return scenarios;
    }

    private List<Map<String, Object>> buildGapZonesBlock(
            ElliottWaveAnalysis analysis, double currentPrice) {

        List<Map<String, Object>> gapZones = new ArrayList<>();

        if (analysis.getSignificantGaps() == null || analysis.getSignificantGaps().isEmpty()) {
            return gapZones;
        }

        List<GapLevel> gaps = analysis.getSignificantGaps().stream()
            .limit(5)
            .collect(Collectors.toList());

        for (GapLevel g : gaps) {
            Map<String, Object> gapMap = new LinkedHashMap<>();
            gapMap.put("timeframe", g.getTimeframe());
            gapMap.put("type", g.getDirection() != null ? g.getDirection().toString() : "UNKNOWN");
            gapMap.put("range_low", g.getGapBottom());
            gapMap.put("range_high", g.getGapTop());

            boolean filled = g.getFillStatus() == GapLevel.GapFillStatus.FILLED;
            gapMap.put("filled", filled);

            gapMap.put("significance", g.getSignificance() != null ? g.getSignificance().toString() : "UNKNOWN");

            String role = g.getGapMidpoint() > currentPrice ? "RESISTANCE" : "SUPPORT";
            gapMap.put("role", role);

            gapZones.add(gapMap);
        }

        return gapZones;
    }

    private Map<String, Object> buildCrossTimeframeSummary(ElliottWaveAnalysis analysis) {
        Map<String, Object> summary = new LinkedHashMap<>();

        if (analysis.getTfContexts() != null) {
            for (Map.Entry<String, TfContext> entry : analysis.getTfContexts().entrySet()) {
                TfContext tfCtx = entry.getValue();
                String narrative = tfCtx.getNarrative();
                String truncated = truncateText(narrative, 60);
                summary.put(entry.getKey(), truncated);
            }
        }

        return summary;
    }

    private Map<String, String> buildPatternCounts(ElliottWaveAnalysis analysis) {
        Map<String, String> counts = new LinkedHashMap<>();

        if (analysis.getAllPatterns() != null && !analysis.getAllPatterns().isEmpty()) {
            Map<String, List<PatternMatch>> byTf = analysis.getAllPatterns().stream()
                .collect(Collectors.groupingBy(PatternMatch::getTimeframe));

            for (Map.Entry<String, List<PatternMatch>> entry : byTf.entrySet()) {
                String tf = entry.getKey();
                List<PatternMatch> patternsForTf = entry.getValue();

                int total = patternsForTf.size();
                long watching = patternsForTf.stream()
                    .filter(p -> p.getStatus() == PatternStatus.WATCHING)
                    .count();
                long building = patternsForTf.stream()
                    .filter(p -> p.getStatus() == PatternStatus.BUILDING)
                    .count();

                Map<String, Object> tfCounts = new LinkedHashMap<>();
                tfCounts.put("total", total);
                tfCounts.put("watching", (int) watching);
                tfCounts.put("building", (int) building);

                counts.put(tf, tfCounts.toString());
            }
        }

        return counts;
    }

    private int estimateTokens(Map<String, Object> payload) {
        try {
            String jsonString = objectMapper.writeValueAsString(payload);
            return (int) Math.ceil(jsonString.length() / 4.0);
        } catch (Exception e) {
            log.warn("Error estimating tokens", e);
            return 0;
        }
    }

    private String extractFirstSentence(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        int dotIndex = text.indexOf('.');
        int newlineIndex = text.indexOf('\n');
        int endIndex = text.length();

        if (dotIndex > 0) {
            endIndex = Math.min(endIndex, dotIndex + 1);
        }
        if (newlineIndex > 0) {
            endIndex = Math.min(endIndex, newlineIndex);
        }

        String result = text.substring(0, endIndex).trim();
        if (result.length() > maxChars) {
            result = result.substring(0, maxChars).trim();
        }

        return result;
    }

    private String truncateText(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        if (text.length() <= maxChars) {
            return text;
        }

        int newlineIndex = text.indexOf('\n');
        if (newlineIndex > 0 && newlineIndex < maxChars) {
            return text.substring(0, newlineIndex).trim();
        }

        return text.substring(0, maxChars).trim();
    }

    private Map<String, Object> buildIndicatorSnapshotBlock(
            Map<String, List<EnrichedPivot>> enrichedPivotsByTf) {

        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (enrichedPivotsByTf == null || enrichedPivotsByTf.isEmpty()) {
            return snapshot;
        }

        for (Map.Entry<String, List<EnrichedPivot>> entry : enrichedPivotsByTf.entrySet()) {
            String tf = entry.getKey();
            List<EnrichedPivot> pivots = entry.getValue();
            if (pivots == null || pivots.isEmpty()) continue;

            List<Map<String, Object>> pivotList = new ArrayList<>();
            int from = Math.max(0, pivots.size() - 2);
            for (int i = from; i < pivots.size(); i++) {
                EnrichedPivot p = pivots.get(i);
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("type",           p.isHigh() ? "HIGH" : "LOW");
                pm.put("price",          p.getPrice());
                pm.put("structure",      p.getStructureLabel() != null ? p.getStructureLabel().toString() : "?");
                pm.put("rsi",            Math.round(p.getRsi() * 10) / 10.0);
                pm.put("macd_hist",      Math.round(p.getMacdHistogram() * 100) / 100.0);
                pm.put("ewo",            Math.round(p.getEwo() * 10) / 10.0);
                pm.put("bb_pct_b",       Math.round(p.getBollingerPctB() * 100) / 100.0);
                pm.put("bb_squeeze",     p.isBollingerSqueeze());
                pm.put("adx",            Math.round(p.getAdx() * 10) / 10.0);
                pm.put("di_plus",        Math.round(p.getDiPlus() * 10) / 10.0);
                pm.put("di_minus",       Math.round(p.getDiMinus() * 10) / 10.0);
                pm.put("ema_stack",      p.getEmaStackOrder() != null ? p.getEmaStackOrder() : "?");
                pm.put("mfi",            Math.round(p.getMfi() * 10) / 10.0);
                pm.put("rel_volume",     Math.round(p.getRelativeVolume() * 10) / 10.0);
                pivotList.add(pm);
            }
            snapshot.put(tf, pivotList);
        }

        return snapshot;
    }

    private Map<String, Object> buildWaveKeyPointsBlock(List<WaveCount> waveCounts) {
        Map<String, Object> block = new LinkedHashMap<>();
        if (waveCounts == null || waveCounts.isEmpty()) return block;

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Kolkata"));

        // Best-scoring WaveCount per timeframe
        Map<String, WaveCount> bestByTf = new LinkedHashMap<>();
        for (WaveCount wc : waveCounts) {
            String tf = wc.getPrimaryTimeframe();
            if (tf == null) continue;
            WaveCount existing = bestByTf.get(tf);
            if (existing == null || wc.totalScore() > existing.totalScore()) {
                bestByTf.put(tf, wc);
            }
        }

        for (Map.Entry<String, WaveCount> entry : bestByTf.entrySet()) {
            WaveCount wc = entry.getValue();
            if (wc.getPivots() == null || wc.getPivotToWave() == null) continue;

            List<Map<String, Object>> points = new ArrayList<>();
            for (EnrichedPivot pivot : wc.getPivots()) {
                WaveLabel label = wc.getPivotToWave().get(pivot.getBarIndex());
                if (label == null || pivot.getTimestamp() == null) continue;
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("label", label.name());
                pt.put("price", Math.round(pivot.getPrice() * 100.0) / 100.0);
                pt.put("date", fmt.format(pivot.getTimestamp()));
                pt.put("type", pivot.isHigh() ? "HIGH" : "LOW");
                points.add(pt);
            }
            if (!points.isEmpty()) block.put(entry.getKey(), points);
        }

        return block;
    }
}
