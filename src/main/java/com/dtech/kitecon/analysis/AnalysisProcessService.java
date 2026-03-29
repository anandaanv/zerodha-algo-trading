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
                                .payloadTokenEstimate(tokenEstimate)
                                .build()
                )
                .build();
    }
}
