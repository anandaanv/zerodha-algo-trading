package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.AnnotationEntry;
import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.SymbolContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pass-1 EwAnnotationIntakeRule (Rule 0.35) — verifies weight-filtered intake of trader
 * annotations into FACT firings. RELIANCE blessed reference: the weight-3 "wave 4C" annotation
 * must surface.
 */
class EwAnnotationIntakeRuleTest {

    private static final EwAnnotationIntakeRule RULE = new EwAnnotationIntakeRule();

    @Test
    void emits_fact_for_weight_3_wave_4C_annotation() {
        AnnotationEntry blessed = new AnnotationEntry(
                "on weekly appears to be in wave 4C; 2 of C or 4 of C; right value is lower",
                3, Instant.parse("2026-05-22T00:00:00Z"));
        SymbolContext ctx = SymbolContext.builder()
                .symbol("RELIANCE").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .annotations(List.of(blessed))
                .build();

        List<Firing> emitted = RULE.evaluate(ctx, List.of());
        assertEquals(1, emitted.size());
        Firing f = emitted.get(0);
        assertEquals(EwAnnotationIntakeRule.RULE_ID, f.getRuleId());
        assertEquals(Family.EW, f.getFamily());
        assertEquals(Pass.P1_STRUCTURAL, f.getPass());
        assertEquals(FiresOn.FACT, f.getFiresOn());
        assertEquals(3, f.getPayload().get("weight"));
        assertTrue(((String) f.getPayload().get("text")).contains("wave 4C"));
    }

    @Test
    void filters_out_weight_1_annotations() {
        // Default min_weight=2. A weight-1 annotation should NOT produce a FACT.
        SymbolContext ctx = SymbolContext.builder()
                .symbol("X").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .annotations(List.of(
                        new AnnotationEntry("hunch about a triangle", 1, Instant.now()),
                        new AnnotationEntry("strong wave-3 setup", 3, Instant.now())
                ))
                .build();

        List<Firing> emitted = RULE.evaluate(ctx, List.of());
        assertEquals(1, emitted.size(), "weight-1 dropped; only weight-3 surfaces");
        assertEquals(3, emitted.get(0).getPayload().get("weight"));
    }

    @Test
    void empty_annotations_returns_empty() {
        SymbolContext ctx = SymbolContext.builder()
                .symbol("X").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .annotations(List.of())
                .build();
        assertTrue(RULE.evaluate(ctx, List.of()).isEmpty());
    }

    @Test
    void null_annotations_handled_gracefully() {
        SymbolContext ctx = SymbolContext.builder()
                .symbol("X").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .build();
        assertTrue(RULE.evaluate(ctx, List.of()).isEmpty());
    }
}
