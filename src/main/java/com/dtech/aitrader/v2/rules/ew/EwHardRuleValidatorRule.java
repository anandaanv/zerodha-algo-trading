package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-3 EW hard-rule validator (canonical Rule 3). For each Pass-2 candidate, applies the
 * inviolable Elliott constraints:
 *
 * <ul>
 *   <li>Impulse: W2 retrace ≤ 100% W1 (else NOT a valid impulse — eliminate)</li>
 *   <li>Impulse: W3 NOT the shortest of W1/W3/W5 (if all three present)</li>
 *   <li>Impulse: W4 does NOT overlap W1 price territory (non-diagonal); the diagonal exception is
 *       not yet handled here — emit a graduated demote rather than categorical eliminate, with a
 *       note that a diagonal re-frame should be considered (deferred to feedback layer)</li>
 *   <li>Zigzag: B does NOT exceed A origin (i.e. for a corrective HL-zigzag from a high, B's
 *       high must stay below A_start; mirror for inverse)</li>
 * </ul>
 *
 * <p>Per spec ab9bd541 §"PASS 3 — VALIDATION": violations emit {@link FiresOn#ELIMINATION}
 * firings refs=[candidate] with {@link PriorDelta#eliminate}. Incomplete-wave checks (e.g. an
 * impulse that has only W0+W1) are SKIPPED silently — Rule 3 can only bind on completed waves.
 *
 * <p>RELIANCE blessed reference {@code cde6bbc9}: MF1 zigzag has A+B completed → B (1473.4) does
 * not exceed A_start (1611.8) → OK. MF2 impulse has only W0+W1 → no checks apply yet. So this
 * rule emits ZERO ELIMINATIONs against the current RELIANCE candidate set — that's correct.
 */
@Component
@Slf4j
public class EwHardRuleValidatorRule implements Rule {

    public static final String RULE_ID = "EW_HARD_RULE_VALIDATOR";

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P3_VALIDATION; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        List<Firing> candidates = priorFirings.stream()
                .filter(f -> f.getFamily() == Family.EW)
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .toList();
        if (candidates.isEmpty()) return List.of();

        List<Firing> out = new ArrayList<>();
        for (Firing cand : candidates) {
            String form = (String) cand.getPayload().get("form");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> assignment =
                    (List<Map<String, Object>>) cand.getPayload().get("pivot_assignment");
            if (assignment == null) continue;

            if ("impulse".equals(form)) {
                Firing violation = checkImpulse(ctx, cand, assignment);
                if (violation != null) out.add(violation);
            } else if ("zigzag".equals(form)) {
                Firing violation = checkZigzag(ctx, cand, assignment);
                if (violation != null) out.add(violation);
            }
            // Other forms (flat, expanded-flat, triangle, WXY) — checks added as those forms land.
        }
        return out;
    }

    // ── impulse Rule 3 ─────────────────────────────────────────────────────────

    private Firing checkImpulse(SymbolContext ctx, Firing candidate,
                                 List<Map<String, Object>> assignment) {
        Double w0 = priceOf(assignment, "W0");
        Double w1End = priceOf(assignment, "W1_end");
        Double w2End = priceOf(assignment, "W2_end");
        Double w3End = priceOf(assignment, "W3_end");
        Double w4End = priceOf(assignment, "W4_end");
        Double w5End = priceOf(assignment, "W5_end");

        if (w0 == null || w1End == null) return null;  // not enough to validate

        boolean bullishImpulse = w1End > w0;  // up-impulse from a LOW

        // W2 ≤ 100% W1
        if (w2End != null) {
            double w1Size = Math.abs(w1End - w0);
            double w2Retrace = Math.abs(w2End - w1End);
            if (w2Retrace > w1Size) {
                return eliminate(ctx, candidate,
                        "Rule 3: W2 retrace " + round(w2Retrace) + " > W1 size " + round(w1Size)
                                + " (100% violated — not a valid impulse)");
            }
            // W2 in the opposite direction of W1 (a bullish W2 must pull DOWN; a bearish UP)
            if (bullishImpulse && w2End > w1End) {
                return eliminate(ctx, candidate,
                        "Rule 3: W2 went the wrong direction (above W1_end in a bullish impulse)");
            }
            if (!bullishImpulse && w2End < w1End) {
                return eliminate(ctx, candidate,
                        "Rule 3: W2 went the wrong direction (below W1_end in a bearish impulse)");
            }
        }

        // W3 not the shortest of W1/W3/W5 (needs all three completed)
        if (w2End != null && w3End != null && w4End != null && w5End != null) {
            double w1Size = Math.abs(w1End - w0);
            double w3Size = Math.abs(w3End - w2End);
            double w5Size = Math.abs(w5End - w4End);
            if (w3Size < w1Size && w3Size < w5Size) {
                return eliminate(ctx, candidate,
                        "Rule 3: W3 (" + round(w3Size) + ") is shortest of W1 ("
                                + round(w1Size) + ") / W5 (" + round(w5Size)
                                + ") — eliminate impulse (or re-frame as diagonal)");
            }
        }

        // W4 must NOT overlap W1 price territory (non-diagonal)
        if (w4End != null) {
            // W1 territory = [min(W0,W1_end), max(W0,W1_end)]
            double w1Lo = Math.min(w0, w1End);
            double w1Hi = Math.max(w0, w1End);
            if (w4End >= w1Lo && w4End <= w1Hi) {
                return eliminate(ctx, candidate,
                        "Rule 3: W4 (" + round(w4End) + ") overlaps W1 territory ["
                                + round(w1Lo) + ", " + round(w1Hi)
                                + "] — non-diagonal impulse eliminated (consider diagonal re-frame)");
            }
        }
        return null;
    }

    // ── zigzag Rule 3 ──────────────────────────────────────────────────────────

    private Firing checkZigzag(SymbolContext ctx, Firing candidate,
                                List<Map<String, Object>> assignment) {
        Double aStart = priceOf(assignment, "A_start");
        Double aEnd = priceOf(assignment, "A_end");
        Double bEnd = priceOf(assignment, "B_end");
        if (aStart == null || aEnd == null || bEnd == null) return null;

        boolean aDown = aStart > aEnd;  // a corrective-from-HIGH descends in A
        if (aDown) {
            // B's high must NOT exceed A's start (else the corrective structure is broken)
            if (bEnd > aStart) {
                return eliminate(ctx, candidate,
                        "Rule 3 (zigzag): B_end " + round(bEnd) + " exceeds A_start "
                                + round(aStart) + " — corrective broken");
            }
        } else {
            // upside corrective: B's low must NOT undercut A's start
            if (bEnd < aStart) {
                return eliminate(ctx, candidate,
                        "Rule 3 (zigzag): B_end " + round(bEnd) + " undercuts A_start "
                                + round(aStart) + " — corrective broken");
            }
        }
        return null;
    }

    // ── firing factory ─────────────────────────────────────────────────────────

    private Firing eliminate(SymbolContext ctx, Firing candidate, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("violated_rule", "Rule 3");
        payload.put("reason", reason);
        payload.put("eliminated_form", candidate.getPayload().get("form"));
        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P3_VALIDATION)
                .firesOn(FiresOn.ELIMINATION)
                .refs(List.of(candidate.getId()))
                .priorDelta(PriorDelta.eliminate(reason, "3"))
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Double priceOf(List<Map<String, Object>> assignment, String role) {
        for (Map<String, Object> m : assignment) {
            if (role.equals(m.get("role"))) {
                Object p = m.get("price");
                if (p instanceof Number n) return n.doubleValue();
                return null;
            }
        }
        return null;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
