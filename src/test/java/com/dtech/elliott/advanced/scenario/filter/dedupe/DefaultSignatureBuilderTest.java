package com.dtech.elliott.advanced.scenario.filter.dedupe;

import com.dtech.elliott.advanced.common.enums.Direction;
import com.dtech.elliott.advanced.common.enums.ExpectedMoveType;
import com.dtech.elliott.advanced.common.enums.StructureFamily;
import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.NormalizedScenario;
import com.dtech.elliott.advanced.scenario.filter.domain.ScenarioSignature;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSignatureBuilderTest {

    private DefaultSignatureBuilder builder;
    private FilterConfig config;

    @BeforeEach
    void setUp() {
        builder = new DefaultSignatureBuilder();
        config = FilterConfig.defaults();
    }

    private NormalizedScenario ns(Direction dir, ExpectedMoveType emt, StructureFamily sf, double invalidation) {
        return new NormalizedScenario(
                "S1", "NIFTY", "15m", dir, emt, sf,
                List.of(), List.of(), invalidation, 25.0, 0.0,
                0.5, 0.5, 0.5, 0.2, 0.5, false, false,
                ScenarioStatus.ACTIVE_ALTERNATE, Map.of(), List.of(), List.of()
        );
    }

    @Test
    void same_scenario_produces_same_signature() {
        NormalizedScenario s1 = ns(Direction.UP, ExpectedMoveType.CONTINUATION_UP, StructureFamily.CORRECTIVE, 1800.0);
        NormalizedScenario s2 = ns(Direction.UP, ExpectedMoveType.CONTINUATION_UP, StructureFamily.CORRECTIVE, 1800.0);
        assertEquals(builder.build(s1, config), builder.build(s2, config));
    }

    @Test
    void different_direction_produces_different_signature() {
        NormalizedScenario s1 = ns(Direction.UP, ExpectedMoveType.CONTINUATION_UP, StructureFamily.CORRECTIVE, 1800.0);
        NormalizedScenario s2 = ns(Direction.DOWN, ExpectedMoveType.CONTINUATION_DOWN, StructureFamily.CORRECTIVE, 1800.0);
        assertNotEquals(builder.build(s1, config), builder.build(s2, config));
    }

    @Test
    void invalidation_bucket_is_rounded() {
        NormalizedScenario s = ns(Direction.UP, ExpectedMoveType.CONTINUATION_UP, StructureFamily.CORRECTIVE, 1824.0);
        ScenarioSignature sig = builder.build(s, config);
        // 1824 / 50 = 36.48, rounded = 36
        assertEquals(36L, sig.roundedInvalidationBucket());
    }
}
