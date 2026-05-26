package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.SymbolContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EwClusterConfluenceRule — spec-derived test.
 *
 * <p><b>Requirement source:</b>
 * <ul>
 *   <li>SPEC-005 ({@code ab9bd541}): "if a candidate's target/invalidation coincides with a Pass-1
 *       cluster, GRADUATED boost (Rule 7: corrective + Wk cluster + Wk divergence → prior≥0.35).
 *       (RELIANCE: MF1's 1290 target sits on the long-term-support cluster.)"</li>
 *   <li>BLESSED cde6bbc9 ({@code 48130d04}): the ~1290-1307 and ~1473-1489 Wk clusters are real.
 *       MF1 zigzag's counter-extreme (1290) and B-end (1473.4) sit on them; MF2 impulse's W0
 *       (1290) and W1-end (1473.4) also sit on them.</li>
 *   <li>Owner eval ({@code f73d1417}): "MF1's ~1152 target / 1290 invalidation coincide with the
 *       ~1290 support cluster → confluence bump (Rule 7)."</li>
 *   <li>Convergence Q5 ({@code 9c60e777}): one firing per (candidate, alignment) — locked.</li>
 * </ul>
 *
 * <p><b>Test contract (independent of current code):</b>
 * Each test asserts behavior the spec promises. If the rule's implementation disagrees, that's a
 * spec-vs-code investigation, not a test to weaken. Numbers used here all trace to the blessed
 * reference {@code 48130d04} — no invented values.
 */
class EwClusterConfluenceRuleTest {

    private final EwClusterConfluenceRule rule = new EwClusterConfluenceRule();

    @Test
    void mf1_zigzag_with_levels_on_blessed_clusters_emits_per_alignment_boosts() {
        // Blessed MF1 zigzag has A_end=1290 (on ~1290-1307 cluster, centre 1306.8) and
        // B_end=1473.4 (on ~1473-1489 cluster, centre 1474.3). Per spec, alignments → GRADUATED
        // boost. Per Q5, one firing per (candidate, alignment).
        Firing mf1 = zigzagCandidate("mf1", 1611.8, 1290.0, 1473.4);
        Firing supportCluster = clusterFact("c1290", 1306.8, "support", 3);
        Firing resistanceCluster = clusterFact("c1473", 1474.3, "resistance", 3);

        List<Firing> emitted = rule.evaluate(blankCtx(),
                List.of(supportCluster, resistanceCluster, mf1));

        assertTrue(emitted.size() >= 2,
                "blessed MF1 has at least two cluster alignments (1290↔1290-cluster, 1473.4↔1473-cluster); got "
                        + emitted.size());
        for (Firing f : emitted) {
            assertEquals(EwClusterConfluenceRule.RULE_ID, f.getRuleId());
            assertEquals(Pass.P4_CLASSIFICATION, f.getPass());
            assertEquals(FiresOn.CLASSIFICATION, f.getFiresOn());
            assertEquals(Family.EW, f.getFamily());
            assertEquals(List.of("mf1"), f.getRefs(),
                    "each alignment firing references the boosted candidate");
            assertEquals(PriorDelta.Kind.GRADUATED, f.getPriorDelta().kind(),
                    "spec mandates GRADUATED — not FLOOR_SET, not CATEGORICAL");
            assertTrue(f.getPriorDelta().graduatedDelta() > 0.0,
                    "confluence boosts must be POSITIVE (Rule 7 prior bump)");
        }

        // Cross-check: cluster centres in the firings come from the Pass-1 FACTs (no invention).
        for (Firing f : emitted) {
            double centre = ((Number) f.getPayload().get("cluster_centre")).doubleValue();
            assertTrue(centre == 1306.8 || centre == 1474.3,
                    "cluster_centre must echo a Pass-1 FACT centre; got " + centre);
        }
    }

    @Test
    void mf2_impulse_with_levels_on_blessed_clusters_emits_boosts() {
        // Blessed MF2 impulse: W0 1290 + W1_end 1473.4 — same level pair, different EW roles.
        // Both should also produce confluence boosts (same spec: structural level on cluster).
        Firing mf2 = impulseCandidate("mf2", 1290.0, 1473.4);
        Firing supportCluster = clusterFact("c1290", 1306.8, "support", 3);
        Firing resistanceCluster = clusterFact("c1473", 1474.3, "resistance", 3);

        List<Firing> emitted = rule.evaluate(blankCtx(),
                List.of(supportCluster, resistanceCluster, mf2));
        assertTrue(emitted.size() >= 2,
                "MF2 impulse W0 + W1_end land on the same two blessed clusters; got " + emitted.size());
        for (Firing f : emitted) {
            assertTrue(f.getPriorDelta().graduatedDelta() > 0.0);
        }
    }

    @Test
    void candidate_levels_far_from_any_cluster_no_firings() {
        // Cluster is at 1306.8. Candidate levels at 1611.8 / 1820 / 1900 — all >10% away.
        // Spec band default 2%. No alignment → no firing.
        Firing offMark = zigzagCandidate("off", 1820.0, 1611.8, 1900.0);
        Firing supportCluster = clusterFact("c1290", 1306.8, "support", 3);

        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(supportCluster, offMark));
        assertTrue(emitted.isEmpty(),
                "candidate with no level within band of any cluster must not fire");
    }

    @Test
    void no_cluster_facts_no_firings() {
        // Pass-1 emitted no clusters (e.g. early-data symbol). Pass-4 confluence has nothing to
        // align against → no firings.
        Firing mf1 = zigzagCandidate("mf1", 1611.8, 1290.0, 1473.4);
        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(mf1));
        assertTrue(emitted.isEmpty(),
                "no Pass-1 cluster facts ⇒ no confluence boosts (rule is gated on cluster facts)");
    }

    @Test
    void eliminated_candidates_skipped() {
        // Per Path A invariant: any candidate eliminated by Pass-3 must not receive Pass-4 boosts.
        Firing mf1 = zigzagCandidate("mf1", 1611.8, 1290.0, 1473.4);
        Firing supportCluster = clusterFact("c1290", 1306.8, "support", 3);
        Firing elim = Firing.builder()
                .id("elim-1").ruleId(EwHardRuleValidatorRule.RULE_ID).family(Family.EW)
                .pass(Pass.P3_VALIDATION).firesOn(FiresOn.ELIMINATION)
                .refs(List.of("mf1"))
                .priorDelta(PriorDelta.eliminate("test", "3"))
                .build();
        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(supportCluster, mf1, elim));
        assertTrue(emitted.isEmpty(),
                "eliminated candidates skip Pass-4 confluence boosts");
    }

    @Test
    void non_ew_clusters_ignored() {
        // A PATTERN-family fact must not be treated as an EW cluster. The rule reads only
        // EwWkClusterScanRule FACTs (family=EW, fires_on=FACT).
        Firing mf1 = zigzagCandidate("mf1", 1611.8, 1290.0, 1473.4);
        Firing patternFact = Firing.builder()
                .id("pat-1").ruleId("DOUBLE_BOTTOM_NECKLINE")
                .family(Family.PATTERN).pass(Pass.P1_STRUCTURAL).firesOn(FiresOn.FACT)
                .payload(Map.of("centre", 1306.8))
                .build();
        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(patternFact, mf1));
        assertTrue(emitted.isEmpty(), "non-EW facts must not seed EW confluence");
    }

    @Test
    void deviation_within_band_pct_passes() {
        // Per spec: band default 2%. A level 1.5% from cluster centre IS within band → fire.
        // Cluster 1306.8; level 1290.0 ⇒ deviation (1306.8−1290)/1306.8 = 1.29% ≤ 2% → align.
        Firing mf1 = zigzagCandidate("mf1", 1611.8, 1290.0, 1473.4);
        Firing supportCluster = clusterFact("c1290", 1306.8, "support", 3);

        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(supportCluster, mf1));
        boolean any1290 = emitted.stream().anyMatch(f -> {
            double dev = ((Number) f.getPayload().get("deviation_pct")).doubleValue();
            return dev > 0.0 && dev <= 2.0;
        });
        assertTrue(any1290, "1290↔1306.8 deviation 1.29% must align (≤ 2% band)");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static SymbolContext blankCtx() {
        return SymbolContext.builder()
                .symbol("RELIANCE").tf("Week").asOf(LocalDate.of(2026, 5, 22)).build();
    }

    private static Firing zigzagCandidate(String id, double aStart, double aEnd, double bEnd) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "zigzag");
        p.put("pivot_assignment", List.of(
                role("A_start", aStart),
                role("A_end", aEnd),
                role("B_end", bEnd)));
        return cand(id, p, 0.45);
    }

    private static Firing impulseCandidate(String id, double w0, double w1End) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "impulse");
        p.put("pivot_assignment", List.of(
                role("W0", w0),
                role("W1_end", w1End)));
        return cand(id, p, 0.25);
    }

    private static Firing cand(String id, Map<String, Object> payload, double basePrior) {
        return Firing.builder()
                .id(id).ruleId(EwEnumerationRule.RULE_ID).family(Family.EW)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(basePrior).payload(payload).build();
    }

    private static Firing clusterFact(String id, double centre, String roleStr, int touchCount) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("centre", centre);
        p.put("role", roleStr);
        p.put("touch_count", touchCount);
        return Firing.builder()
                .id(id).ruleId(EwWkClusterScanRule.RULE_ID).family(Family.EW)
                .pass(Pass.P1_STRUCTURAL).firesOn(FiresOn.FACT)
                .payload(p).build();
    }

    private static Map<String, Object> role(String name, double price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", name);
        m.put("price", price);
        m.put("date", null);
        return m;
    }
}
