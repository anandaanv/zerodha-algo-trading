package com.dtech.ta.elliott.filter.compress;

import com.dtech.ta.elliott.model.Direction;
import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.*;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultScenarioCompressorTest {

    private DefaultScenarioCompressor compressor;
    private FilterConfig config;

    @BeforeEach
    void setUp() {
        compressor = new DefaultScenarioCompressor();
        config = FilterConfig.defaults();
    }

    private ScenarioConflictSet emptyConflict() {
        return new ScenarioConflictSet("NIFTY", "15m", List.of(), List.of(), List.of(),
                "LOW_CONVICTION_NOISE", List.of());
    }

    private ScenarioFamilyCandidate family(String id, double finalScore, ScenarioStatus status,
                                            ScenarioFamilyType type, Direction dir) {
        FamilyScore score = new FamilyScore(finalScore, finalScore, 0.5, 0.1, 0.1, finalScore, 0.5, finalScore);
        return new ScenarioFamilyCandidate(
                id, "NIFTY", "15m", type, dir,
                List.of(), List.of(), List.of(), List.of(),
                1800.0, 0.0, 0.0, false, false, false,
                status, score, List.of(), List.of(), Map.of()
        );
    }

    @Test
    void highest_ranked_becomes_leading() {
        var f1 = family("F1", 0.8, ScenarioStatus.LEADING,
                ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, Direction.UP);
        var f2 = family("F2", 0.5, ScenarioStatus.ACTIVE_ALTERNATE,
                ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_COMPRESSION, Direction.UP);
        var f3 = family("F3", 0.3, ScenarioStatus.WEAK_ALTERNATE,
                ScenarioFamilyType.LOW_CONVICTION_REVERSAL_SEED, Direction.UP);
        var result = compressor.compress(List.of(f1, f2, f3), emptyConflict(), config);
        assertNotNull(result.leadingScenario());
        assertEquals(0.8, result.leadingScenario().score().finalRankScore());
    }

    @Test
    void alternates_capped_at_max() {
        var families = List.of(
                family("F1", 0.9, ScenarioStatus.LEADING, ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, Direction.UP),
                family("F2", 0.7, ScenarioStatus.ACTIVE_ALTERNATE, ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_COMPRESSION, Direction.UP),
                family("F3", 0.6, ScenarioStatus.ACTIVE_ALTERNATE, ScenarioFamilyType.SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT, Direction.NEUTRAL),
                family("F4", 0.5, ScenarioStatus.ACTIVE_ALTERNATE, ScenarioFamilyType.ONGOING_CORRECTIVE_COMPLEXITY, Direction.NEUTRAL),
                family("F5", 0.4, ScenarioStatus.WEAK_ALTERNATE, ScenarioFamilyType.LOW_CONVICTION_REVERSAL_SEED, Direction.UP)
        );
        var result = compressor.compress(families, emptyConflict(), config);
        // maxActiveAlternates = 2
        assertTrue(result.activeAlternates().size() <= config.maxActiveAlternates());
    }

    @Test
    void invalidated_families_in_separate_bucket() {
        var f1 = family("F1", 0.8, ScenarioStatus.ACTIVE_ALTERNATE,
                ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, Direction.UP);
        var f2 = family("F2", 0.7, ScenarioStatus.ACTIVE_ALTERNATE,
                ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_COMPRESSION, Direction.UP);
        var f3 = family("F3", 0.6, ScenarioStatus.INVALIDATED,
                ScenarioFamilyType.BEARISH_REVERSAL_AFTER_TERMINAL_PUSH, Direction.DOWN);
        var result = compressor.compress(List.of(f1, f2, f3), emptyConflict(), config);
        assertEquals(1, result.invalidatedFamilies().size());
        assertEquals("F3", result.invalidatedFamilies().get(0).id());
    }

    @Test
    void no_trade_noise_suppressed() {
        // config.suppressNoTradeNoiseFamilies = true (default)
        var bullish = family("F1", 0.6, ScenarioStatus.ACTIVE_ALTERNATE,
                ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, Direction.UP);
        var noise = family("F2", 0.3, ScenarioStatus.ACTIVE_ALTERNATE,
                ScenarioFamilyType.NO_TRADE_NOISE, Direction.NEUTRAL);
        var result = compressor.compress(List.of(bullish, noise), emptyConflict(), config);
        // noise should be suppressed
        boolean noisePresent = (result.leadingScenario() != null
                && result.leadingScenario().familyType() == ScenarioFamilyType.NO_TRADE_NOISE)
                || result.activeAlternates().stream().anyMatch(f -> f.familyType() == ScenarioFamilyType.NO_TRADE_NOISE)
                || result.weakAlternates().stream().anyMatch(f -> f.familyType() == ScenarioFamilyType.NO_TRADE_NOISE);
        assertFalse(noisePresent);
    }
}
