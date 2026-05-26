package com.dtech.aitrader.v2.rules.patterns;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.IndicatorAccessor;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

/**
 * PatternEwRelevanceGateRule — locks owner correction {@code 174fbb2a} Point 2: forming patterns
 * surface only when EW-relevant. Confirmed patterns stay tradable independently (per
 * {@code 2c1fb814}); forming patterns require proximity to an admitted EW signature's watch or
 * invalidation level.
 */
class PatternEwRelevanceGateRuleTest {

    private final PatternEwRelevanceGateRule rule = new PatternEwRelevanceGateRule();

    @Test
    void forming_pattern_near_admitted_ew_level_passes_no_elimination() {
        // Admitted EW signature has watch level at 1290; forming HnS trigger at 1295 (within
        // 1.5×ATR=45 band). Pattern passes the gate ⇒ no elimination firing.
        SymbolContext ctx = ctxWithAtr(30.0);
        Firing ewSig = admittedSignature("sig1", 1290.0);
        Firing forming = patternCandidate("pat1", "HNS_DETECT", 1295.0, "forming", 50.0);

        List<Firing> out = rule.evaluate(ctx, List.of(ewSig, forming));
        assertTrue(out.isEmpty(),
                "forming pattern within 1.5×ATR of EW level ⇒ no elimination");
    }

    @Test
    void forming_pattern_far_from_any_ew_level_eliminated() {
        // EW level 1290; forming pattern at 1500 (210 away, far beyond 45 band).
        SymbolContext ctx = ctxWithAtr(30.0);
        Firing ewSig = admittedSignature("sig1", 1290.0);
        Firing forming = patternCandidate("pat1", "HNS_DETECT", 1500.0, "forming", 70.0);

        List<Firing> out = rule.evaluate(ctx, List.of(ewSig, forming));
        assertEquals(1, out.size());
        Firing elim = out.get(0);
        assertEquals(FiresOn.ELIMINATION, elim.getFiresOn());
        assertEquals(PriorDelta.Kind.CATEGORICAL_ELIMINATE, elim.getPriorDelta().kind());
        assertEquals("pat1", elim.getPayload().get("eliminated_pattern_id"));
        assertEquals(List.of("pat1"), elim.getRefs());
    }

    @Test
    void confirmed_pattern_far_from_ew_level_NOT_eliminated() {
        // Confirmed patterns are tradable standalone (per 2c1fb814) — even far from EW levels,
        // they survive. Only forming patterns require EW relevance.
        SymbolContext ctx = ctxWithAtr(30.0);
        Firing ewSig = admittedSignature("sig1", 1290.0);
        Firing confirmed = patternCandidate("pat1", "DOUBLE_TOP_DETECT", 1500.0, "confirmed", 100.0);

        List<Firing> out = rule.evaluate(ctx, List.of(ewSig, confirmed));
        assertTrue(out.isEmpty(),
                "confirmed patterns survive the gate even without EW proximity");
    }

    @Test
    void no_admitted_signatures_all_forming_patterns_eliminated() {
        SymbolContext ctx = ctxWithAtr(30.0);
        Firing forming1 = patternCandidate("p1", "HNS_DETECT", 1290.0, "forming", 50.0);
        Firing forming2 = patternCandidate("p2", "DOUBLE_TOP_DETECT", 1500.0, "forming", 70.0);
        Firing confirmed = patternCandidate("p3", "INVERSE_HNS_DETECT", 1100.0, "confirmed", 100.0);

        List<Firing> out = rule.evaluate(ctx, List.of(forming1, forming2, confirmed));
        assertEquals(2, out.size(),
                "no EW context ⇒ all forming patterns eliminated, confirmed survives");
        assertTrue(out.stream().allMatch(f -> f.getFiresOn() == FiresOn.ELIMINATION));
    }

    @Test
    void invalidation_level_also_counts_as_ew_relevant() {
        // Owner: pattern at an EW reversal/continuation zone counts. Invalidation levels qualify.
        SymbolContext ctx = ctxWithAtr(30.0);
        Firing ewSig = admittedSignatureWithInvalidation("sig1", 1290.0, 1611.8);
        Firing forming = patternCandidate("pat1", "HNS_DETECT", 1605.0, "forming", 70.0);

        List<Firing> out = rule.evaluate(ctx, List.of(ewSig, forming));
        assertTrue(out.isEmpty(),
                "forming pattern near an EW invalidation level passes the gate");
    }

    @Test
    void non_admitted_signature_levels_do_not_count() {
        SymbolContext ctx = ctxWithAtr(30.0);
        Firing pendingSig = signatureFiring("sig1", "PENDING", 1290.0);
        Firing forming = patternCandidate("pat1", "HNS_DETECT", 1295.0, "forming", 50.0);

        List<Firing> out = rule.evaluate(ctx, List.of(pendingSig, forming));
        assertEquals(1, out.size(),
                "PENDING signatures don't contribute EW levels; forming pattern eliminated");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static SymbolContext ctxWithAtr(double atr) {
        BarSeries series = buildSeries(50);
        IndicatorAccessor acc = Mockito.spy(new IndicatorAccessor(series));
        doReturn(atr).when(acc).atr(Mockito.anyInt());
        return SymbolContext.builder()
                .symbol("TEST").tf("Day").asOf(LocalDate.of(2026, 5, 25))
                .series(series).indicators(acc).build();
    }

    private static Firing patternCandidate(String id, String ruleId, double triggerPrice,
                                              String status, double completionPct) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("status", status);
        p.put("completion_pct", completionPct);
        p.put("trigger_price", triggerPrice);
        p.put("bias", "SHORT");
        return Firing.builder()
                .id(id).ruleId(ruleId).family(Family.PATTERN)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.4).payload(p)
                .build();
    }

    private static Firing admittedSignature(String id, double watchPrice) {
        return signatureFiring(id, "ADMITTED", watchPrice);
    }

    private static Firing admittedSignatureWithInvalidation(String id, double watchPrice,
                                                              double invalidationPrice) {
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("watch", List.of(priceLevel(watchPrice)));
        derived.put("invalidation", List.of(priceLevel(invalidationPrice)));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("admission_state", "ADMITTED");
        p.put("form_name", "zigzag");
        p.put("derived_levels", derived);
        return Firing.builder()
                .id(id).ruleId("EW_SIGNATURE_EVALUATION").family(Family.EW)
                .pass(Pass.P5_CONFIRMATION).firesOn(FiresOn.CONFIRMATION)
                .payload(p)
                .build();
    }

    private static Firing signatureFiring(String id, String state, double watchPrice) {
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("watch", List.of(priceLevel(watchPrice)));
        derived.put("invalidation", List.of());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("admission_state", state);
        p.put("form_name", "zigzag");
        p.put("derived_levels", derived);
        return Firing.builder()
                .id(id).ruleId("EW_SIGNATURE_EVALUATION").family(Family.EW)
                .pass(Pass.P5_CONFIRMATION).firesOn(FiresOn.CONFIRMATION)
                .payload(p)
                .build();
    }

    private static Map<String, Object> priceLevel(double price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("price", price);
        m.put("label", "test");
        m.put("basis", "test");
        return m;
    }

    private static BarSeries buildSeries(int bars) {
        BarSeries s = new BaseBarSeriesBuilder().withName("test").build();
        Instant t0 = Instant.parse("2024-01-01T05:30:00Z");
        for (int i = 0; i < bars; i++) {
            double c = 1300.0 + i * 0.5;
            s.addBar(BarsLoader.getBar(c, c + 5, c - 5, c, 1_000.0,
                    t0.plus(Duration.ofHours(i + 1)), Duration.ofHours(1)));
        }
        return s;
    }
}
