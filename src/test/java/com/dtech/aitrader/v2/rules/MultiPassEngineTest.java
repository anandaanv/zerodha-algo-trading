package com.dtech.aitrader.v2.rules;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the multi-pass execution semantics: passes run P0→P6 regardless of input order; later
 * passes see all firings from earlier passes; rule exceptions don't kill the engine.
 *
 * <p>Pure unit test — uses fake {@link Rule} stubs and a minimal {@link SymbolContext}. No Spring,
 * no ta4j, no DB.
 */
class MultiPassEngineTest {

    private static final SymbolContext CTX = SymbolContext.builder()
            .symbol("TEST")
            .tf("Day")
            .asOf(LocalDate.of(2024, 6, 1))
            .build();

    @Test
    void passes_execute_in_order_regardless_of_input_order() {
        // Track the order each rule fires so we can assert P1 before P3 before P6.
        List<Pass> firedIn = new ArrayList<>();

        Rule r6 = rule("R6", Pass.P6_SYNTHESIS, (ctx, prior) -> { firedIn.add(Pass.P6_SYNTHESIS); return List.of(); });
        Rule r3 = rule("R3", Pass.P3_VALIDATION, (ctx, prior) -> { firedIn.add(Pass.P3_VALIDATION); return List.of(); });
        Rule r1 = rule("R1", Pass.P1_STRUCTURAL, (ctx, prior) -> { firedIn.add(Pass.P1_STRUCTURAL); return List.of(); });

        // Deliberately shuffled input order — engine must sort by pass.
        new MultiPassEngine().run(CTX, List.of(r6, r3, r1));

        assertEquals(List.of(Pass.P1_STRUCTURAL, Pass.P3_VALIDATION, Pass.P6_SYNTHESIS), firedIn);
    }

    @Test
    void later_pass_sees_earlier_firings() {
        Firing p1Firing = Firing.builder()
                .ruleId("P1_RULE").symbol("TEST").tf("Day").asOf(CTX.getAsOf())
                .family(Family.STRUCTURE).pass(Pass.P1_STRUCTURAL).firesOn(FiresOn.FACT)
                .payload(java.util.Map.of("fact", "macd-bull-cross"))
                .build();

        Rule producer = rule("P1_RULE", Pass.P1_STRUCTURAL, (ctx, prior) -> {
            assertEquals(0, prior.size(), "P1 should see no prior firings");
            return List.of(p1Firing);
        });
        AtomicInteger consumerCount = new AtomicInteger();
        Rule consumer = rule("P4_RULE", Pass.P4_CLASSIFICATION, (ctx, prior) -> {
            consumerCount.incrementAndGet();
            assertEquals(1, prior.size(), "P4 should see the P1 firing");
            assertEquals("P1_RULE", prior.get(0).getRuleId());
            return List.of();
        });

        new MultiPassEngine().run(CTX, List.of(consumer, producer));
        assertEquals(1, consumerCount.get(), "consumer rule must have fired");
    }

    @Test
    void rule_exception_does_not_kill_engine() {
        Rule explodes = rule("BAD", Pass.P1_STRUCTURAL, (ctx, prior) -> {
            throw new RuntimeException("simulated rule bug");
        });
        AtomicInteger survivorRan = new AtomicInteger();
        Rule survivor = rule("OK", Pass.P3_VALIDATION, (ctx, prior) -> {
            survivorRan.incrementAndGet();
            return List.of();
        });

        List<Firing> out = new MultiPassEngine().run(CTX, List.of(explodes, survivor));
        assertEquals(1, survivorRan.get(), "later rule must still run after earlier rule threw");
        assertNotNull(out);
    }

    @Test
    void empty_rule_list_returns_empty() {
        assertTrue(new MultiPassEngine().run(CTX, List.of()).isEmpty());
    }

    @Test
    void engine_does_not_mutate_input_priorFirings() {
        // Rules must NOT be able to mutate the priorFirings list they see — append-only invariant.
        Rule p2 = rule("P2", Pass.P2_ENUMERATION, (ctx, prior) -> {
            Firing f = Firing.builder()
                    .ruleId("P2").symbol("TEST").tf("Day").asOf(CTX.getAsOf())
                    .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                    .build();
            return List.of(f);
        });
        Rule p3 = rule("P3", Pass.P3_VALIDATION, (ctx, prior) -> {
            // Try to add — must throw UnsupportedOperationException
            try {
                prior.add(null);
                fail("priorFirings list must be unmodifiable");
            } catch (UnsupportedOperationException expected) {
                // good
            }
            return List.of();
        });
        new MultiPassEngine().run(CTX, List.of(p2, p3));
    }

    @Test
    void firings_get_default_ids() {
        Rule p1 = rule("P1", Pass.P1_STRUCTURAL, (ctx, prior) -> List.of(
                Firing.builder()
                        .ruleId("P1").symbol("TEST").tf("Day").asOf(CTX.getAsOf())
                        .pass(Pass.P1_STRUCTURAL).firesOn(FiresOn.FACT)
                        .build()));
        List<Firing> out = new MultiPassEngine().run(CTX, List.of(p1));
        assertEquals(1, out.size());
        assertNotNull(out.get(0).getId(), "Firing.id must auto-generate via @Builder.Default");
        assertFalse(out.get(0).getId().isBlank());
    }

    // ──────────────────────────────────────────────────────────────────────────

    /** Functional Rule for clean test wiring. */
    private static Rule rule(String id, Pass pass,
                              java.util.function.BiFunction<SymbolContext, List<Firing>, List<Firing>> body) {
        return new Rule() {
            @Override public String ruleId() { return id; }
            @Override public Pass pass() { return pass; }
            @Override public Family family() { return Family.STRUCTURE; }
            @Override public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
                return body.apply(ctx, priorFirings);
            }
        };
    }
}
