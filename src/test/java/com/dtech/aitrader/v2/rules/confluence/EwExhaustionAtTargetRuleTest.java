package com.dtech.aitrader.v2.rules.confluence;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.IndicatorAccessor;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EwExhaustionAtTargetRule — locks the brainstorm-Q1..Q5 positions from owner memo
 * {@code 34418c54}. The rule must:
 *
 * <ul>
 *   <li>Emit nothing without an ADMITTED signature firing carrying a watch level.</li>
 *   <li>When current price is within ATR-band of a watch level, emit a CONFIRMATION firing
 *       whose tilt_score is deterministically composed (0.34 proximity + 0.33 exhaustion +
 *       0.33 reversal-pattern, capped 1.0).</li>
 *   <li>When a reversal pattern fires near the level, additionally emit a WATCH firing carrying
 *       an invalidation_stamp for the continuation hypothesis (owner Q5).</li>
 *   <li>Never carry a non-zero PriorDelta — tilt is for ORDERING the level-map, not ranking
 *       (SPEC reframe 159ba913).</li>
 * </ul>
 *
 * <p>Fixture choice: ranges are inflated so ATR ≈ 30 and the proximity band ≈ 45. This lets
 * tests place targets within realistic offsets from current price (10–15 INR) and still hit the
 * band. Real-market RELIANCE Wk ATR is ~40 INR — this fixture is in the same order of magnitude.
 */
class EwExhaustionAtTargetRuleTest {

    private final EwExhaustionAtTargetRule rule = new EwExhaustionAtTargetRule();

    @Test
    void proximity_hit_no_evidence_emits_continuation_tilt_with_score_034() {
        // SHORT hypothesis (target 1290 below current 1300), no patterns, hist held flat at zero
        // (no exhaustion).
        SymbolContext ctx = ctxWithStubIndicators(1300.0, /*atr=*/ 30.0,
                /*histNow=*/ 0.0, /*histPrev=*/ 0.0);
        Firing sig = admittedSignature("zz1", "zigzag",
                List.of(watchLevel(1290.0, "A_end", "B_end-|A|")));

        List<Firing> out = rule.evaluate(ctx, List.of(sig));
        assertEquals(1, out.size(),
                "no evidence ⇒ one CONFIRMATION firing only (no invalidation stamp)");
        Firing tilt = out.get(0);
        assertEquals(FiresOn.CONFIRMATION, tilt.getFiresOn());
        assertEquals("CONTINUATION", tilt.getPayload().get("tilt_direction"));
        assertEquals(0.34, (double) tilt.getPayload().get("tilt_score"), 1e-9);
    }

    @Test
    void proximity_with_reversal_pattern_emits_reversal_tilt_and_invalidation_stamp() {
        // LONG hypothesis: current 1500, target 1510 above. DT (bias=SHORT) fires near 1510 —
        // reversal pattern at upper target. Hist held flat — no exhaustion contribution.
        SymbolContext ctx = ctxWithStubIndicators(1500.0, 30.0, 0.0, 0.0);
        Firing sig = admittedSignature("imp1", "impulse",
                List.of(watchLevel(1510.0, "W3_target", "W3=1.618*W1")));
        Firing pattern = patternCandidate("p1", "DOUBLE_TOP_DETECT", 1510.0, "SHORT");

        List<Firing> out = rule.evaluate(ctx, List.of(sig, pattern));
        assertEquals(2, out.size(),
                "REVERSAL + reversal-pattern ⇒ one CONFIRMATION tilt + one WATCH invalidation_stamp");

        Firing tilt = firstWithFiresOn(out, FiresOn.CONFIRMATION);
        assertEquals("REVERSAL", tilt.getPayload().get("tilt_direction"));
        assertEquals(0.67, (double) tilt.getPayload().get("tilt_score"), 1e-9,
                "proximity 0.34 + reversal-pattern 0.33 = 0.67 (no exhaustion)");

        Firing stamp = firstWithFiresOn(out, FiresOn.WATCH);
        assertEquals(Boolean.TRUE, stamp.getPayload().get("invalidation_stamp"));
        assertEquals(1510.0, ((Number) stamp.getPayload().get("beyond_level")).doubleValue(), 1e-9);
        assertEquals("above", stamp.getPayload().get("beyond_direction"));
        assertEquals("reversal-pattern-at-target", stamp.getPayload().get("source"));
        assertEquals("LONG", stamp.getPayload().get("for_hypothesis_direction"));
    }

    @Test
    void proximity_with_exhaustion_only_emits_reversal_tilt_no_stamp() {
        // Build a context whose IndicatorAccessor reports contracting MACD-histogram. LONG
        // hypothesis ⇒ contracting hist tilts BEARISH ⇒ counts as reversal-direction exhaustion.
        SymbolContext ctx = ctxWithStubIndicators(
                1500.0,                       // current price proxy
                /*atr=*/ 30.0,
                /*histNow=*/ 0.5,
                /*histPrev=*/ 5.0);          // |hist| shrinking ⇒ contraction
        Firing sig = admittedSignature("imp1", "impulse",
                List.of(watchLevel(1510.0, "W3_target", "extension")));

        List<Firing> out = rule.evaluate(ctx, List.of(sig));
        assertEquals(1, out.size(), "exhaustion only ⇒ tilt firing, no invalidation stamp");
        Firing tilt = out.get(0);
        assertEquals("REVERSAL", tilt.getPayload().get("tilt_direction"));
        assertEquals(0.67, (double) tilt.getPayload().get("tilt_score"), 1e-9,
                "proximity 0.34 + exhaustion 0.33 = 0.67");
    }

    @Test
    void proximity_with_pattern_and_exhaustion_caps_at_one() {
        SymbolContext ctx = ctxWithStubIndicators(1500.0, 30.0, 0.5, 5.0);
        Firing sig = admittedSignature("imp1", "impulse",
                List.of(watchLevel(1510.0, "W3_target", "extension")));
        Firing pattern = patternCandidate("p1", "DOUBLE_TOP_DETECT", 1510.0, "SHORT");

        List<Firing> out = rule.evaluate(ctx, List.of(sig, pattern));
        Firing tilt = firstWithFiresOn(out, FiresOn.CONFIRMATION);
        assertEquals(1.0, (double) tilt.getPayload().get("tilt_score"), 1e-9);
    }

    @Test
    void no_proximity_no_firings() {
        SymbolContext ctx = ctxWithStubIndicators(1300.0, 30.0, 0.0, 0.0);
        Firing sig = admittedSignature("zz1", "zigzag",
                List.of(watchLevel(367.0, "C_target", "B_end-|A|")));  // 933 away from price
        List<Firing> out = rule.evaluate(ctx, List.of(sig));
        assertTrue(out.isEmpty(), "current price far from target ⇒ no firing");
    }

    @Test
    void no_admitted_signatures_no_firings() {
        SymbolContext ctx = ctxWithStubIndicators(1300.0, 30.0, 0.0, 0.0);
        Firing sig = signatureFiring("zz1", "zigzag", "PENDING",
                List.of(watchLevel(1290.0, "A_end", "B_end-|A|")));
        List<Firing> out = rule.evaluate(ctx, List.of(sig));
        assertTrue(out.isEmpty(), "non-ADMITTED signature firings must be ignored");
    }

    @Test
    void prior_delta_is_zero_on_every_emitted_firing() {
        SymbolContext ctx = ctxWithStubIndicators(1500.0, 30.0, 0.0, 0.0);
        Firing sig = admittedSignature("imp1", "impulse",
                List.of(watchLevel(1510.0, "W3_target", "extension")));
        Firing pattern = patternCandidate("p1", "DOUBLE_TOP_DETECT", 1510.0, "SHORT");

        List<Firing> out = rule.evaluate(ctx, List.of(sig, pattern));
        for (Firing f : out) {
            assertEquals(PriorDelta.Kind.GRADUATED, f.getPriorDelta().kind());
            assertEquals(0.0, f.getPriorDelta().graduatedDelta(), 1e-9,
                    "every confluence firing must carry zero prior_delta — ordering only, not ranking");
        }
    }

    @Test
    void forming_pattern_filtered_until_Q8_ratified() {
        // SPEC-008 (e332be7f) lets pattern rules emit FORMING firings (status=forming,
        // completion_pct < 100). Until owner ratifies the completion_pct → tilt scaling formula
        // (Q8 from 77cd09ee), tilt is contributed ONLY by status=confirmed (or status absent for
        // backwards compat). A forming HnS at completion=50 must NOT trigger REVERSAL tilt.
        SymbolContext ctx = ctxWithStubIndicators(1500.0, 30.0, 0.0, 0.0);
        Firing sig = admittedSignature("imp1", "impulse",
                List.of(watchLevel(1510.0, "W3_target", "extension")));
        Firing formingPattern = patternCandidateWithStatus("p1", "HNS_DETECT", 1510.0, "SHORT",
                "forming", 50);

        List<Firing> out = rule.evaluate(ctx, List.of(sig, formingPattern));
        assertEquals(1, out.size(), "forming pattern must not trigger invalidation_stamp");
        Firing tilt = out.get(0);
        assertEquals(FiresOn.CONFIRMATION, tilt.getFiresOn());
        assertEquals("CONTINUATION", tilt.getPayload().get("tilt_direction"),
                "forming pattern excluded from tilt → CONTINUATION default");
        assertEquals(0.34, (double) tilt.getPayload().get("tilt_score"), 1e-9,
                "tilt_score is proximity-only when pattern is filtered out");
    }

    @Test
    void tilt_basis_records_Q4_position_audit() {
        SymbolContext ctx = ctxWithStubIndicators(1300.0, 30.0, 0.0, 0.0);
        Firing sig = admittedSignature("zz1", "zigzag",
                List.of(watchLevel(1290.0, "A_end", "B_end-|A|")));
        Firing tilt = rule.evaluate(ctx, List.of(sig)).get(0);
        @SuppressWarnings("unchecked")
        List<String> basis = (List<String>) tilt.getPayload().get("tilt_basis");
        assertNotNull(basis);
        assertTrue(basis.stream().anyMatch(s -> s.startsWith("Q4")),
                "tilt_basis must record the Q4 no-ranking position for audit; got " + basis);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Firing firstWithFiresOn(List<Firing> firings, FiresOn kind) {
        return firings.stream().filter(f -> f.getFiresOn() == kind).findFirst().orElseThrow(
                () -> new AssertionError("no firing with FiresOn=" + kind + " in " + firings.size()));
    }

    private static Map<String, Object> watchLevel(double price, String label, String basis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("price", price);
        m.put("label", label);
        m.put("basis", basis);
        return m;
    }

    private static Firing admittedSignature(String id, String form, List<Map<String, Object>> watch) {
        return signatureFiring(id, form, "ADMITTED", watch);
    }

    private static Firing signatureFiring(String id, String form, String state,
                                            List<Map<String, Object>> watch) {
        Map<String, Object> derivedLevels = new LinkedHashMap<>();
        derivedLevels.put("watch", watch);
        derivedLevels.put("invalidation", List.of());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("signature_rule_id", form + "-test");
        payload.put("form_name", form);
        payload.put("admission_state", state);
        payload.put("derived_levels", derivedLevels);

        return Firing.builder()
                .id(id).ruleId("EW_SIGNATURE_EVALUATION").family(Family.EW)
                .pass(Pass.P5_CONFIRMATION).firesOn(FiresOn.CONFIRMATION)
                .refs(List.of("cand-" + id))
                .priorDelta(PriorDelta.graduated(0.0, "", "test"))
                .payload(payload)
                .build();
    }

    private static Firing patternCandidate(String id, String ruleId, double triggerPrice, String bias) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trigger_price", triggerPrice);
        payload.put("bias", bias);
        return Firing.builder()
                .id(id).ruleId(ruleId).family(Family.PATTERN)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.4)
                .payload(payload)
                .build();
    }

    private static Firing patternCandidateWithStatus(String id, String ruleId, double triggerPrice,
                                                       String bias, String status, int completionPct) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trigger_price", triggerPrice);
        payload.put("bias", bias);
        payload.put("status", status);
        payload.put("completion_pct", completionPct);
        return Firing.builder()
                .id(id).ruleId(ruleId).family(Family.PATTERN)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.4)
                .payload(payload)
                .build();
    }

    /**
     * Test-only context using a stub IndicatorAccessor that returns hand-picked ATR + MACD-hist
     * values. Lets the rule-test exercise the exhaustion path without depending on the precise
     * shape of a synthetic price series. The underlying series is still a real ta4j object so
     * other accessor calls (RSI, EMA) don't NPE — but the values that matter to the rule come
     * from the stub.
     */
    private static SymbolContext ctxWithStubIndicators(double price, double atr,
                                                        double histNow, double histPrev) {
        BarSeries series = buildWideRangeSeries(price, 60);
        IndicatorAccessor acc = new StubIndicatorAccessor(series, atr, histNow, histPrev);
        return buildContext(series, acc, price);
    }

    private static SymbolContext buildContext(BarSeries series, IndicatorAccessor acc, double price) {
        Map<String, List<MarketStructurePoint>> byTf = new LinkedHashMap<>();
        byTf.put("OneHour", List.of(pivot("2026-04-29T09:45:00Z", price, PivotType.LOW)));
        return SymbolContext.builder()
                .symbol("TEST").tf("Day").asOf(LocalDate.of(2026, 5, 25))
                .series(series)
                .indicators(acc)
                .pivots(List.of(pivot("2026-04-29T09:45:00Z", price, PivotType.LOW)))
                .pivotsByTf(byTf)
                .build();
    }

    private static BarSeries buildWideRangeSeries(double price, int bars) {
        BarSeries s = new BaseBarSeriesBuilder().withName("wide").build();
        Instant t0 = Instant.parse("2024-01-01T05:30:00Z");
        for (int i = 0; i < bars; i++) {
            // ±15 range each bar gives ATR ≈ 30; closes alternate ±0.5 around price so RSI stays
            // near 50 (no incidental divergence signal).
            double c = price + ((i % 2 == 0) ? 0.5 : -0.5);
            double o = price - 0.1;
            double h = price + 15.0;
            double l = price - 15.0;
            s.addBar(BarsLoader.getBar(o, h, l, c, 1_000.0,
                    t0.plus(Duration.ofHours(i + 1)), Duration.ofHours(1)));
        }
        return s;
    }

    private static MarketStructurePoint pivot(String iso, double price, PivotType type) {
        return MarketStructurePoint.builder()
                .pivotType(type).structureLabel(StructureLabel.FIRST)
                .timestamp(Instant.parse(iso)).price(price)
                .atrAtPivot(5.0).rsiAtPivot(50.0).build();
    }

    /**
     * Returns hand-picked ATR + MACD-histogram values; other indicators delegate to the parent
     * (which still works against the underlying real BarSeries). Used to make the rule test
     * agnostic of the precise indicator shape on a tiny synthetic series.
     */
    private static final class StubIndicatorAccessor extends IndicatorAccessor {
        private final double stubAtr;
        private final double stubHistNow;
        private final double stubHistPrev;

        StubIndicatorAccessor(BarSeries series, double atr, double histNow, double histPrev) {
            super(series);
            this.stubAtr = atr;
            this.stubHistNow = histNow;
            this.stubHistPrev = histPrev;
        }

        @Override public double atr(int idx) { return stubAtr; }

        @Override
        public double macdHistogram(int idx) {
            int endIdx = series().getEndIndex();
            if (idx == endIdx) return stubHistNow;
            if (idx == endIdx - ExhaustionDetector.MACD_LOOKBACK_BARS) return stubHistPrev;
            return super.macdHistogram(idx);
        }
    }
}
