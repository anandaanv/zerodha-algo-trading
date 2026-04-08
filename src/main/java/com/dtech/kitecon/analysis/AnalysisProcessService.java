package com.dtech.kitecon.analysis;

import com.dtech.kitecon.analysis.dto.NormalizedScenario;
import com.dtech.kitecon.analysis.dto.ProcessAnalysisResponse;
import com.dtech.kitecon.analysis.dto.ProcessAnalysisResponse.ProcessingStats;
import com.dtech.ta.elliott.ElliottWaveAnalysis;
import com.dtech.ta.elliott.PatternMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisProcessService {

    private final PatternDeduplicator deduplicator;
    private final ScoreNormalizer scoreNormalizer;
    private final DecisionPointGenerator decisionPointGenerator;
    private final OutputRestructurer outputRestructurer;
    private final AIPayloadGenerator aiPayloadGenerator;
    private final IndicatorSnapshotFormatter indicatorSnapshotFormatter;

    public ProcessAnalysisResponse process(
            ElliottWaveAnalysis analysis,
            String symbol,
            double currentPrice,
            String entryTf) {

        // Step 1: Count patterns before deduplication
        List<PatternMatch> allPatterns = analysis.getAllPatterns();
        if (allPatterns == null) {
            allPatterns = List.of();
        }
        int before = deduplicator.countBeforeDedup(allPatterns);

        // Step 2: Deduplicate patterns
        List<PatternMatch> dedupedPatterns = deduplicator.deduplicate(allPatterns);
        int after = dedupedPatterns.size();

        // Step 2b: Historical / proximity filters
        int currentBarIndex = getCurrentBarIndex(analysis);
        int removedHistorical = 0;
        int removedProximity  = 0;
        if (currentPrice > 0) {
            List<PatternMatch> filtered = new java.util.ArrayList<>();
            for (PatternMatch p : dedupedPatterns) {
                // Rule A: CONFIRMED patterns whose levels are all >15% away
                if (p.getStatus() == com.dtech.ta.elliott.PatternStatus.CONFIRMED) {
                    Double sup = p.getSupport();
                    Double res = p.getResistance();
                    double distSup = sup != null ? Math.abs(sup - currentPrice) / currentPrice : 0;
                    double distRes = res != null ? Math.abs(res - currentPrice) / currentPrice : 0;
                    boolean bothFar = (sup == null || distSup > 0.15) && (res == null || distRes > 0.15);
                    if (bothFar) { removedProximity++; continue; }
                }
                // Rule B: Temporal staleness — most recent pivot >200 bars old AND price outside range >10%
                if (p.getPivotBarIndices() != null && !p.getPivotBarIndices().isEmpty() && currentBarIndex > 0) {
                    int mostRecentBar = p.getPivotBarIndices().stream().mapToInt(Integer::intValue).max().orElse(0);
                    int age = currentBarIndex - mostRecentBar;
                    if (age > 200) {
                        Double sup = p.getSupport();
                        Double res = p.getResistance();
                        double lo = (sup != null && res != null) ? Math.min(sup, res) : (sup != null ? sup : (res != null ? res : currentPrice));
                        double hi = (sup != null && res != null) ? Math.max(sup, res) : (sup != null ? sup : (res != null ? res : currentPrice));
                        boolean outsideRange = currentPrice < lo * 0.90 || currentPrice > hi * 1.10;
                        if (outsideRange) { removedHistorical++; continue; }
                    }
                }
                filtered.add(p);
            }
            dedupedPatterns = filtered;
        }

        // Step 2c: Mark historical wave counts
        int waveCountsHistorical = 0;
        int waveCountsActive     = 0;
        int hypothesesSuppressed = 0;
        if (analysis.getWaveCounts() != null && currentBarIndex > 0) {
            for (com.dtech.ta.elliott.WaveCount wc : analysis.getWaveCounts()) {
                if (wc.getPivots() == null || wc.getPivots().isEmpty()) continue;
                boolean complete = wc.getCurrentWaveInProgress() == com.dtech.ta.elliott.WaveLabel.UNKNOWN;
                int lastBar = wc.getPivots().stream().mapToInt(com.dtech.ta.elliott.EnrichedPivot::getBarIndex).max().orElse(0);
                int age = currentBarIndex - lastBar;
                if (complete && age > 150) {
                    wc.setHistorical(true);
                    waveCountsHistorical++;
                } else {
                    waveCountsActive++;
                }
            }
        }

        // Step 3: Normalize scenarios with deduplicated patterns
        List<NormalizedScenario> normalizedScenarios = scoreNormalizer.normalize(
                analysis.getScenarios(),
                dedupedPatterns,
                analysis.getNestedCorrectiveContexts(),
                analysis.getSignificantGaps()
        );

        // Step 4: Generate decision point
        String decisionPoint = decisionPointGenerator.generate(
                analysis,
                dedupedPatterns,
                normalizedScenarios,
                currentPrice,
                entryTf,
                symbol
        );

        // Step 5: Restructure to human-readable format
        String humanReadable = outputRestructurer.restructure(
                analysis,
                dedupedPatterns,
                normalizedScenarios,
                decisionPoint,
                symbol,
                currentPrice
        );

        // Step 5b: Append indicator snapshot
        String indicatorSnapshot = indicatorSnapshotFormatter.format(analysis.getEnrichedPivots());
        if (indicatorSnapshot != null && !indicatorSnapshot.isBlank()) {
            humanReadable = humanReadable + "\n\n" + indicatorSnapshot;
        }

        // Step 6: Generate AI payload
        Map<String, Object> aiPayload = aiPayloadGenerator.generate(
                analysis,
                dedupedPatterns,
                normalizedScenarios,
                decisionPoint,
                currentPrice,
                entryTf,
                symbol
        );

        // Step 7: Extract and remove token estimate
        int tokenEstimate = (int) aiPayload.getOrDefault("_token_estimate", 0);
        aiPayload.remove("_token_estimate");

        // Log processing summary
        log.info(
                "[AnalysisProcess] {} patterns → {} after dedup, token estimate: {}",
                before,
                after,
                tokenEstimate
        );

        // Step 8: Build and return response
        return ProcessAnalysisResponse.builder()
                .humanReadable(humanReadable)
                .aiPayload(aiPayload)
                .processingStats(
                        ProcessingStats.builder()
                                .patternsBefore(before)
                                .patternsAfter(after)
                                .patternsRemovedHistorical(removedHistorical)
                                .patternsRemovedProximity(removedProximity)
                                .waveCountsHistorical(waveCountsHistorical)
                                .waveCountsActive(waveCountsActive)
                                .hypothesesSuppressedHistorical(hypothesesSuppressed)
                                .payloadTokenEstimate(tokenEstimate)
                                .build()
                )
                .build();
    }

    private int getCurrentBarIndex(ElliottWaveAnalysis analysis) {
        if (analysis == null || analysis.getWaveCounts() == null) return 0;
        return analysis.getWaveCounts().stream()
            .filter(wc -> wc.getPivots() != null && !wc.getPivots().isEmpty())
            .flatMap(wc -> wc.getPivots().stream())
            .mapToInt(com.dtech.ta.elliott.EnrichedPivot::getBarIndex)
            .max().orElse(0);
    }
}
