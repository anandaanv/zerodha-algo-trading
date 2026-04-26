package com.dtech.ta.elliott.filter.orchestration;

import com.dtech.ta.elliott.filter.api.ScenarioFilterEngine;
import com.dtech.ta.elliott.filter.classify.DefaultScenarioFamilyClassifier;
import com.dtech.ta.elliott.filter.classify.ScenarioFamilyClassifier;
import com.dtech.ta.elliott.filter.compress.DefaultScenarioCompressor;
import com.dtech.ta.elliott.filter.compress.ScenarioCompressor;
import com.dtech.ta.elliott.filter.conflict.ConflictResolver;
import com.dtech.ta.elliott.filter.conflict.DefaultConflictResolver;
import com.dtech.ta.elliott.filter.dedupe.DefaultScenarioDeduplicator;
import com.dtech.ta.elliott.filter.dedupe.DefaultSignatureBuilder;
import com.dtech.ta.elliott.filter.dedupe.ScenarioDeduplicator;
import com.dtech.ta.elliott.filter.dedupe.SignatureBuilder;
import com.dtech.ta.elliott.filter.normalize.DefaultScenarioNormalizer;
import com.dtech.ta.elliott.filter.normalize.ScenarioNormalizer;
import com.dtech.ta.elliott.filter.prune.DefaultHardPruner;
import com.dtech.ta.elliott.filter.prune.HardPruner;
import com.dtech.ta.elliott.filter.score.DefaultFamilyScorer;
import com.dtech.ta.elliott.filter.score.FamilyScorer;
import com.dtech.ta.elliott.filter.summary.DefaultHumanResearchSummaryBuilder;
import com.dtech.ta.elliott.filter.summary.DefaultReasoningPayloadCompressionBuilder;
import com.dtech.ta.elliott.filter.summary.HumanResearchSummaryBuilder;
import com.dtech.ta.elliott.filter.summary.ReasoningPayloadCompressionBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScenarioFilterEngineConfig {

    @Bean
    public SignatureBuilder signatureBuilder() {
        return new DefaultSignatureBuilder();
    }

    @Bean
    public HardPruner hardPruner() {
        return new DefaultHardPruner();
    }

    @Bean
    public ScenarioNormalizer scenarioNormalizer() {
        return new DefaultScenarioNormalizer();
    }

    @Bean
    public ScenarioDeduplicator scenarioDeduplicator(SignatureBuilder signatureBuilder) {
        return new DefaultScenarioDeduplicator(signatureBuilder);
    }

    @Bean
    public ScenarioFamilyClassifier scenarioFamilyClassifier() {
        return new DefaultScenarioFamilyClassifier();
    }

    @Bean
    public ConflictResolver conflictResolver() {
        return new DefaultConflictResolver();
    }

    @Bean
    public FamilyScorer familyScorer() {
        return new DefaultFamilyScorer();
    }

    @Bean
    public ScenarioCompressor scenarioCompressor() {
        return new DefaultScenarioCompressor();
    }

    @Bean
    public HumanResearchSummaryBuilder humanResearchSummaryBuilder() {
        return new DefaultHumanResearchSummaryBuilder();
    }

    @Bean
    public ReasoningPayloadCompressionBuilder reasoningPayloadCompressionBuilder() {
        return new DefaultReasoningPayloadCompressionBuilder();
    }

    @Bean
    public ScenarioFilterEngine scenarioFilterEngine(
            HardPruner hardPruner,
            ScenarioNormalizer scenarioNormalizer,
            ScenarioDeduplicator scenarioDeduplicator,
            ScenarioFamilyClassifier scenarioFamilyClassifier,
            ConflictResolver conflictResolver,
            FamilyScorer familyScorer,
            ScenarioCompressor scenarioCompressor,
            HumanResearchSummaryBuilder humanResearchSummaryBuilder,
            ReasoningPayloadCompressionBuilder reasoningPayloadCompressionBuilder) {
        return new DefaultScenarioFilterEngine(
                hardPruner, scenarioNormalizer, scenarioDeduplicator,
                scenarioFamilyClassifier, conflictResolver, familyScorer,
                scenarioCompressor, humanResearchSummaryBuilder, reasoningPayloadCompressionBuilder
        );
    }
}
