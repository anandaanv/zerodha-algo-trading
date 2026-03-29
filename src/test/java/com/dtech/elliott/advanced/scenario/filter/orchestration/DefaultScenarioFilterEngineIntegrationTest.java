package com.dtech.elliott.advanced.scenario.filter.orchestration;

import com.dtech.elliott.advanced.domain.scenario.Scenario;
import com.dtech.elliott.advanced.scenario.filter.api.ScenarioFilterEngine;
import com.dtech.elliott.advanced.scenario.filter.classify.DefaultScenarioFamilyClassifier;
import com.dtech.elliott.advanced.scenario.filter.compress.DefaultScenarioCompressor;
import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.conflict.DefaultConflictResolver;
import com.dtech.elliott.advanced.scenario.filter.dedupe.DefaultScenarioDeduplicator;
import com.dtech.elliott.advanced.scenario.filter.dedupe.DefaultSignatureBuilder;
import com.dtech.elliott.advanced.scenario.filter.domain.FilteredScenarioSet;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioFamilyType;
import com.dtech.elliott.advanced.scenario.filter.normalize.DefaultScenarioNormalizer;
import com.dtech.elliott.advanced.scenario.filter.prune.DefaultHardPruner;
import com.dtech.elliott.advanced.scenario.filter.score.DefaultFamilyScorer;
import com.dtech.elliott.advanced.scenario.filter.summary.DefaultHumanResearchSummaryBuilder;
import com.dtech.elliott.advanced.scenario.filter.summary.DefaultReasoningPayloadCompressionBuilder;
import com.dtech.ta.elliott.PatternMatch;
import com.dtech.ta.elliott.PatternType;
import com.dtech.ta.elliott.WaveScenario;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultScenarioFilterEngineIntegrationTest {

    private ScenarioFilterEngine engine;
    private FilterConfig config;

    @BeforeEach
    void setUp() {
        var sigBuilder = new DefaultSignatureBuilder();
        engine = new DefaultScenarioFilterEngine(
                new DefaultHardPruner(),
                new DefaultScenarioNormalizer(),
                new DefaultScenarioDeduplicator(sigBuilder),
                new DefaultScenarioFamilyClassifier(),
                new DefaultConflictResolver(),
                new DefaultFamilyScorer(),
                new DefaultScenarioCompressor(),
                new DefaultHumanResearchSummaryBuilder(),
                new DefaultReasoningPayloadCompressionBuilder()
        );
        config = FilterConfig.defaults();
    }

    private Scenario scenario(String id, WaveScenario.ScenarioDirection dir,
                               double invalidation, ScenarioStatus status, double score,
                               PatternType alignedType) {
        PatternMatch pm = PatternMatch.builder()
                .type(alignedType)
                .confidence(0.75)
                .build();
        return new Scenario(id, "NIFTY", "15m", dir, invalidation, "wave reason",
                status, score, List.of(pm), List.of(), List.of(pm), Map.of(), List.of(), List.of());
    }

    @Test
    void compression_case() {
        // Three RANGE_RESOLUTION scenarios with compression patterns
        var s1 = scenario("S1", WaveScenario.ScenarioDirection.RANGE_RESOLUTION,
                1800.0, ScenarioStatus.ACTIVE_ALTERNATE, 55.0, PatternType.SYMMETRICAL_TRIANGLE);
        var s2 = scenario("S2", WaveScenario.ScenarioDirection.RANGE_RESOLUTION,
                1810.0, ScenarioStatus.ACTIVE_ALTERNATE, 50.0, PatternType.ASCENDING_CHANNEL);
        var s3 = scenario("S3", WaveScenario.ScenarioDirection.RANGE_RESOLUTION,
                1820.0, ScenarioStatus.ACTIVE_ALTERNATE, 45.0, PatternType.DESCENDING_CHANNEL);
        FilteredScenarioSet result = engine.filter(List.of(s1, s2, s3), config);
        assertNotNull(result);
        assertNotNull(result.humanSummary());
        assertNotNull(result.reasoningPayloadCompression());
    }

    @Test
    void bullish_continuation_case() {
        var s1 = scenario("S1", WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1750.0, ScenarioStatus.LEADING, 70.0, PatternType.FALLING_WEDGE);
        var s2 = scenario("S2", WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1760.0, ScenarioStatus.ACTIVE_ALTERNATE, 65.0, PatternType.ASCENDING_TRIANGLE);
        FilteredScenarioSet result = engine.filter(List.of(s1, s2), config);
        assertNotNull(result.leadingScenario());
        assertEquals(com.dtech.elliott.advanced.common.enums.Direction.UP,
                result.leadingScenario().directionalBias());
    }

    @Test
    void all_invalidated_returns_empty_leading() {
        var s1 = scenario("S1", WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, ScenarioStatus.INVALIDATED, 60.0, PatternType.FALLING_WEDGE);
        var s2 = scenario("S2", WaveScenario.ScenarioDirection.BEARISH_CONTINUATION,
                1900.0, ScenarioStatus.INVALIDATED, 55.0, PatternType.RISING_WEDGE);
        FilteredScenarioSet result = engine.filter(List.of(s1, s2), config);
        assertNull(result.leadingScenario());
        assertNotNull(result.humanSummary());
    }

    @Test
    void null_input_returns_valid_empty_result() {
        FilteredScenarioSet result = engine.filter(null, config);
        assertNotNull(result);
        assertNull(result.leadingScenario());
    }
}
