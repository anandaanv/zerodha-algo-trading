package com.dtech.ta.elliott.filter.orchestration;

import com.dtech.ta.elliott.scenario.Scenario;
import com.dtech.ta.elliott.filter.api.ScenarioFilterEngine;
import com.dtech.ta.elliott.filter.classify.ScenarioFamilyClassifier;
import com.dtech.ta.elliott.filter.compress.ScenarioCompressor;
import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.conflict.ConflictResolver;
import com.dtech.ta.elliott.filter.dedupe.ScenarioDeduplicator;
import com.dtech.ta.elliott.filter.domain.*;
import com.dtech.ta.elliott.filter.normalize.ScenarioNormalizer;
import com.dtech.ta.elliott.filter.prune.HardPruner;
import com.dtech.ta.elliott.filter.score.FamilyScorer;
import com.dtech.ta.elliott.filter.summary.HumanResearchSummaryBuilder;
import com.dtech.ta.elliott.filter.summary.ReasoningPayloadCompressionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

public class DefaultScenarioFilterEngine implements ScenarioFilterEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultScenarioFilterEngine.class);

    private final HardPruner hardPruner;
    private final ScenarioNormalizer normalizer;
    private final ScenarioDeduplicator deduplicator;
    private final ScenarioFamilyClassifier classifier;
    private final ConflictResolver conflictResolver;
    private final FamilyScorer scorer;
    private final ScenarioCompressor compressor;
    private final HumanResearchSummaryBuilder humanSummaryBuilder;
    private final ReasoningPayloadCompressionBuilder payloadBuilder;

    public DefaultScenarioFilterEngine(
            HardPruner hardPruner,
            ScenarioNormalizer normalizer,
            ScenarioDeduplicator deduplicator,
            ScenarioFamilyClassifier classifier,
            ConflictResolver conflictResolver,
            FamilyScorer scorer,
            ScenarioCompressor compressor,
            HumanResearchSummaryBuilder humanSummaryBuilder,
            ReasoningPayloadCompressionBuilder payloadBuilder) {
        this.hardPruner = hardPruner;
        this.normalizer = normalizer;
        this.deduplicator = deduplicator;
        this.classifier = classifier;
        this.conflictResolver = conflictResolver;
        this.scorer = scorer;
        this.compressor = compressor;
        this.humanSummaryBuilder = humanSummaryBuilder;
        this.payloadBuilder = payloadBuilder;
    }

    @Override
    public FilteredScenarioSet filter(List<Scenario> rawScenarios, FilterConfig config) {
        if (rawScenarios == null) rawScenarios = List.of();

        log.debug("ScenarioFilterEngine: starting with {} raw scenarios", rawScenarios.size());

        // Step 1: Hard prune
        List<Scenario> pruned = hardPruner.prune(rawScenarios, config);
        log.debug("After prune: {}", pruned.size());

        // Step 2: Normalize
        List<NormalizedScenario> normalized = normalizer.normalize(pruned, config);
        log.debug("After normalize: {}", normalized.size());

        // Step 3: Deduplicate
        List<ScenarioCluster> clusters = deduplicator.deduplicate(normalized, config);
        log.debug("After dedup: {} clusters", clusters.size());

        // Step 4: Classify
        List<ScenarioFamilyCandidate> families = classifier.classify(clusters, config);
        log.debug("After classify: {} families", families.size());

        // Step 5: Resolve conflicts
        ScenarioConflictSet conflictSet = conflictResolver.resolve(families, config);

        // Step 6: Score
        List<ScenarioFamilyCandidate> scored = scorer.score(families, config);

        // Step 7: Compress
        FilteredScenarioSet compressed = compressor.compress(scored, conflictSet, config);

        // Step 8: Human summary
        HumanResearchSummary humanSummary = humanSummaryBuilder.build(compressed);

        // Step 9: AI reasoning payload
        ReasoningPayloadCompression payload = payloadBuilder.build(compressed);

        // Rebuild with summaries filled in
        FilteredScenarioSet result = new FilteredScenarioSet(
                compressed.symbol(), compressed.anchorTimeframe(),
                compressed.leadingScenario(), compressed.activeAlternates(),
                compressed.weakAlternates(), compressed.invalidatedFamilies(),
                conflictSet, humanSummary, payload
        );

        log.debug("ScenarioFilterEngine: complete. leading={}",
                result.leadingScenario() != null ? result.leadingScenario().familyType() : "none");

        return result;
    }
}
