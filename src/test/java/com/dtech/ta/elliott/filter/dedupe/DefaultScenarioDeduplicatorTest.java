package com.dtech.ta.elliott.filter.dedupe;

import com.dtech.ta.elliott.model.Direction;
import com.dtech.ta.elliott.model.ExpectedMoveType;
import com.dtech.ta.elliott.model.StructureFamily;
import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.NormalizedScenario;
import com.dtech.ta.elliott.filter.domain.ScenarioCluster;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultScenarioDeduplicatorTest {

    private DefaultScenarioDeduplicator deduplicator;
    private FilterConfig config;

    @BeforeEach
    void setUp() {
        deduplicator = new DefaultScenarioDeduplicator(new DefaultSignatureBuilder());
        config = FilterConfig.defaults();
    }

    private NormalizedScenario ns(String id, Direction dir, ExpectedMoveType emt, StructureFamily sf,
                                   double invalidation, List<String> patterns) {
        return new NormalizedScenario(
                id, "NIFTY", "15m", dir, emt, sf,
                List.of(), patterns, invalidation, 25.0, 0.0,
                0.5, 0.5, 0.5, 0.2, 0.5, false, false,
                ScenarioStatus.ACTIVE_ALTERNATE, Map.of(), List.of(), List.of()
        );
    }

    @Test
    void same_signature_grouped_into_one_cluster() {
        NormalizedScenario s1 = ns("S1", Direction.UP, ExpectedMoveType.CONTINUATION_UP,
                StructureFamily.CORRECTIVE, 1800.0, List.of("FALLING_WEDGE"));
        NormalizedScenario s2 = ns("S2", Direction.UP, ExpectedMoveType.CONTINUATION_UP,
                StructureFamily.CORRECTIVE, 1800.0, List.of("FALLING_WEDGE"));
        List<ScenarioCluster> clusters = deduplicator.deduplicate(List.of(s1, s2), config);
        assertEquals(1, clusters.size());
        assertEquals(2, clusters.get(0).members().size());
    }

    @Test
    void merged_cluster_has_combined_patterns() {
        NormalizedScenario s1 = ns("S1", Direction.UP, ExpectedMoveType.CONTINUATION_UP,
                StructureFamily.CORRECTIVE, 1800.0, List.of("FALLING_WEDGE"));
        NormalizedScenario s2 = ns("S2", Direction.UP, ExpectedMoveType.CONTINUATION_UP,
                StructureFamily.CORRECTIVE, 1800.0, List.of("ASCENDING_TRIANGLE"));
        List<ScenarioCluster> clusters = deduplicator.deduplicate(List.of(s1, s2), config);
        assertEquals(1, clusters.size());
        List<String> patterns = clusters.get(0).mergedSupportingPatterns();
        assertTrue(patterns.contains("FALLING_WEDGE"));
        assertTrue(patterns.contains("ASCENDING_TRIANGLE"));
    }
}
