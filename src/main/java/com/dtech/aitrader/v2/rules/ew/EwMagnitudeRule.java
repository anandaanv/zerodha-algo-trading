package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-4 EW magnitude classifier (canonical Rule 0.65). The load-bearing pass — for RELIANCE
 * blessed reference {@code cde6bbc9}, this rule must produce:
 *
 * <ul>
 *   <li>MF1 zigzag: B = (1473.4 − 1290.0) / (1611.8 − 1290.0) = 183.4 / 321.8 = <b>57.0%</b>
 *       → zigzag-B range (38-78%) → <b>verified zigzag</b> — supports MF1.</li>
 *   <li>MF2 impulse: W2 retrace from Hr low 1318.7 → (1473.4 − 1318.7) / (1473.4 − 1290.0)
 *       = 154.7 / 183.4 = <b>84.4%</b> → atypical-W2 (78-90%) → <b>GRADUATED demote</b> on MF2,
 *       flag corrective-not-impulsive alternative.</li>
 * </ul>
 *
 * <p>These two classifications drive MF1 above MF2 in the final fold — they are why the blessed
 * reference picks MF1 as leading.
 *
 * <p>For W2-in-progress (RELIANCE's MF2 case): the rule looks at lower-TF pivots (OneHour first,
 * then Day) for the lowest LOW (bullish impulse) / highest HIGH (bearish impulse) between
 * {@code W1_end} timestamp and {@code asOf}. The blessed reference uses Hr 1318 — same source the
 * rule reads from {@link SymbolContext#getPivotsByTf()}.
 *
 * <p>Rule 0.65 ratio thresholds are coded as named constants. Other waves (W3, W4, W5, A, C)
 * are scaffolded with comments and will be expanded as additional blessed references arrive.
 */
@Component
@Slf4j
public class EwMagnitudeRule implements Rule {

    public static final String RULE_ID = "EW_MAGNITUDE";

    // ── Rule 0.65 thresholds (B-wave / W2 retrace) ─────────────────────────────
    private static final double B_ZIGZAG_LO = 0.38;       // 38%
    private static final double B_ZIGZAG_HI = 0.78;       // 78%   → zigzag-B verified
    private static final double B_ATYPICAL_HI = 0.90;     // 78-90% atypical-B (flat-pending)
    private static final double B_FLAT_HI = 1.05;         // 90-105% flat
    // > 1.05 = expanded-flat

    private static final double W2_LEADING_DIAGONAL_HI = 0.38;
    private static final double W2_WEAK_HI = 0.50;        // 38-50% weak (leading diagonal flag)
    private static final double W2_STANDARD_HI = 0.78;    // 50-78% standard W2
    private static final double W2_ATYPICAL_HI = 0.90;    // 78-90% atypical (corrective alt)
    private static final double W2_NEAR_MAX_HI = 1.00;    // 90-100% demote W2-of-impulse
    // > 1.00 = Rule 3 violation (handled by Pass-3 already)

    // ── Prior deltas per classification ────────────────────────────────────────
    private static final double DELTA_VERIFIED = 0.00;          // already in basePrior
    private static final double DELTA_ATYPICAL_DEMOTE = -0.05;
    private static final double DELTA_NEAR_MAX_DEMOTE = -0.15;
    private static final double DELTA_LEADING_DIAGONAL_FLAG = 0.0;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P4_CLASSIFICATION; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        List<Firing> candidates = priorFirings.stream()
                .filter(f -> f.getFamily() == Family.EW)
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .toList();
        if (candidates.isEmpty()) return List.of();

        // Build the eliminated-set so we skip dead candidates.
        java.util.Set<String> eliminated = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.ELIMINATION && f.getRefs() != null)
                .flatMap(f -> f.getRefs().stream())
                .collect(java.util.stream.Collectors.toSet());

        List<Firing> out = new java.util.ArrayList<>();
        for (Firing cand : candidates) {
            if (eliminated.contains(cand.getId())) continue;
            String form = (String) cand.getPayload().get("form");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> assignment =
                    (List<Map<String, Object>>) cand.getPayload().get("pivot_assignment");
            if (assignment == null) continue;

            if ("zigzag".equals(form)) {
                Firing classification = classifyZigzag(ctx, cand, assignment);
                if (classification != null) out.add(classification);
            } else if ("impulse".equals(form)) {
                Firing classification = classifyImpulse(ctx, cand, assignment);
                if (classification != null) out.add(classification);
            }
        }
        return out;
    }

    // ── zigzag (Rule 0.65 B-wave classification) ───────────────────────────────

    private Firing classifyZigzag(SymbolContext ctx, Firing candidate,
                                    List<Map<String, Object>> assignment) {
        Double aStart = priceOf(assignment, "A_start");
        Double aEnd = priceOf(assignment, "A_end");
        Double bEnd = priceOf(assignment, "B_end");
        if (aStart == null || aEnd == null || bEnd == null) return null;

        double aMag = Math.abs(aEnd - aStart);
        double bMag = Math.abs(bEnd - aEnd);
        if (aMag <= 0) return null;
        double bRatio = bMag / aMag;

        String classification;
        double delta;
        String reason;
        if (bRatio < B_ZIGZAG_LO) {
            classification = "weak-B";
            delta = DELTA_ATYPICAL_DEMOTE;
            reason = "B/A = " + pct(bRatio) + " < 38% — weak or running zigzag-B";
        } else if (bRatio <= B_ZIGZAG_HI) {
            classification = "zigzag-B-verified";
            delta = DELTA_VERIFIED;
            reason = "B/A = " + pct(bRatio) + " in 38-78% range — verified zigzag-B";
        } else if (bRatio <= B_ATYPICAL_HI) {
            classification = "atypical-B";
            delta = DELTA_ATYPICAL_DEMOTE;
            reason = "B/A = " + pct(bRatio) + " atypical (78-90%) — flat/W4-higher-degree alt";
        } else if (bRatio <= B_FLAT_HI) {
            classification = "flat-B";
            delta = DELTA_NEAR_MAX_DEMOTE;
            reason = "B/A = " + pct(bRatio) + " flat range (90-105%)";
        } else {
            classification = "expanded-flat-B";
            delta = DELTA_NEAR_MAX_DEMOTE;
            reason = "B/A = " + pct(bRatio) + " > 105% — expanded flat";
        }

        return emit(ctx, candidate, "zigzag-B",
                Map.of(
                        "a_magnitude_pts", round(aMag),
                        "b_magnitude_pts", round(bMag),
                        "b_over_a_pct", round(bRatio * 100, 2),
                        "classification", classification),
                delta, "0.65", reason);
    }

    // ── impulse (Rule 0.65 W2 classification — handles W2-in-progress) ────────

    private Firing classifyImpulse(SymbolContext ctx, Firing candidate,
                                     List<Map<String, Object>> assignment) {
        Double w0 = priceOf(assignment, "W0");
        Double w1End = priceOf(assignment, "W1_end");
        Double w2End = priceOf(assignment, "W2_end");
        String w1EndDateStr = dateOf(assignment, "W1_end");
        if (w0 == null || w1End == null) return null;

        double w1Mag = Math.abs(w1End - w0);
        if (w1Mag <= 0) return null;
        boolean bullish = w1End > w0;

        double w2Retrace;
        boolean inProgress;
        String w2SourceTf;
        Double w2ExtremePrice = null;
        String w2ExtremeDate = null;

        if (w2End != null) {
            w2Retrace = Math.abs(w2End - w1End);
            inProgress = false;
            w2SourceTf = "Week";
            w2ExtremePrice = w2End;
            w2ExtremeDate = dateOf(assignment, "W2_end");
        } else {
            // W2 in progress: look for lowest LOW (bullish) / highest HIGH (bearish) in lower-TF
            // pivots between W1_end timestamp and asOf. Prefer Hr (OneHour) — blessed reference
            // explicitly cites Hr 1318. Fall back to Day if Hr absent.
            MarketStructurePoint found = findInProgressExtreme(ctx, w1EndDateStr, bullish, "OneHour");
            if (found == null) {
                found = findInProgressExtreme(ctx, w1EndDateStr, bullish, "Day");
                w2SourceTf = "Day";
            } else {
                w2SourceTf = "OneHour";
            }
            if (found == null) return null;  // no lower-TF data — can't classify
            w2ExtremePrice = found.getPrice();
            w2ExtremeDate = LocalDate.ofInstant(found.getTimestamp(),
                    ZoneId.of("Asia/Kolkata")).toString();
            w2Retrace = Math.abs(w2ExtremePrice - w1End);
            inProgress = true;
        }

        double w2Ratio = w2Retrace / w1Mag;

        String classification;
        double delta;
        String reason;
        if (w2Ratio > 1.0) {
            classification = "rule3-violation";  // shouldn't reach here — Pass-3 catches first
            delta = DELTA_NEAR_MAX_DEMOTE;
            reason = "W2 retrace " + pct(w2Ratio) + " > 100% — Rule 3 violation";
        } else if (w2Ratio > W2_ATYPICAL_HI) {
            classification = "near-max-W2";
            delta = DELTA_NEAR_MAX_DEMOTE;
            reason = "W2 retrace " + pct(w2Ratio) + " in 90-100% — demote W2-of-impulse";
        } else if (w2Ratio > W2_STANDARD_HI) {
            classification = "atypical-W2";
            delta = DELTA_ATYPICAL_DEMOTE;
            reason = "W2 retrace " + pct(w2Ratio) + " atypical (78-90%) — flag corrective alt";
        } else if (w2Ratio > W2_WEAK_HI) {
            classification = "standard-W2";
            delta = DELTA_VERIFIED;
            reason = "W2 retrace " + pct(w2Ratio) + " in 50-78% standard range";
        } else if (w2Ratio > W2_LEADING_DIAGONAL_HI) {
            classification = "weak-W2";
            delta = DELTA_LEADING_DIAGONAL_FLAG;
            reason = "W2 retrace " + pct(w2Ratio) + " weak (38-50%) — leading-diagonal flag";
        } else {
            classification = "leading-diagonal";
            delta = DELTA_LEADING_DIAGONAL_FLAG;
            reason = "W2 retrace " + pct(w2Ratio) + " < 38% — leading-diagonal primary";
        }

        Map<String, Object> classPayload = new LinkedHashMap<>();
        classPayload.put("w1_magnitude_pts", round(w1Mag));
        classPayload.put("w2_retrace_pts", round(w2Retrace));
        classPayload.put("w2_over_w1_pct", round(w2Ratio * 100, 2));
        classPayload.put("classification", classification);
        classPayload.put("w2_in_progress", inProgress);
        classPayload.put("w2_source_tf", w2SourceTf);
        classPayload.put("w2_extreme_price", w2ExtremePrice);
        classPayload.put("w2_extreme_date", w2ExtremeDate);

        return emit(ctx, candidate, "impulse-W2", classPayload, delta, "0.65", reason);
    }

    private MarketStructurePoint findInProgressExtreme(SymbolContext ctx, String w1EndDateStr,
                                                         boolean bullish, String tf) {
        if (ctx.getPivotsByTf() == null) return null;
        List<MarketStructurePoint> pivots = ctx.getPivotsByTf().get(tf);
        if (pivots == null || pivots.isEmpty() || w1EndDateStr == null) return null;
        Instant after = LocalDate.parse(w1EndDateStr)
                .atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();

        // For bullish impulse: look for LOWEST LOW after w1_end → that's the deepest W2 retrace.
        // For bearish impulse: look for HIGHEST HIGH after w1_end.
        MarketStructurePoint best = null;
        PivotType targetKind = bullish ? PivotType.LOW : PivotType.HIGH;
        for (MarketStructurePoint p : pivots) {
            if (p.getPivotType() != targetKind) continue;
            if (p.getTimestamp() == null || !p.getTimestamp().isAfter(after)) continue;
            if (best == null
                    || (bullish && p.getPrice() < best.getPrice())
                    || (!bullish && p.getPrice() > best.getPrice())) {
                best = p;
            }
        }
        return best;
    }

    // ── firing factory ─────────────────────────────────────────────────────────

    private Firing emit(SymbolContext ctx, Firing candidate, String wave,
                          Map<String, Object> payload, double delta,
                          String ruleRef, String reason) {
        Map<String, Object> full = new LinkedHashMap<>(payload);
        full.put("wave", wave);
        full.put("rule_ref", ruleRef);
        full.put("reason", reason);

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P4_CLASSIFICATION)
                .firesOn(FiresOn.CLASSIFICATION)
                .refs(List.of(candidate.getId()))
                .priorDelta(PriorDelta.graduated(delta, reason, ruleRef))
                .roundNum(1)
                .payload(full)
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

    private static String dateOf(List<Map<String, Object>> assignment, String role) {
        for (Map<String, Object> m : assignment) {
            if (role.equals(m.get("role"))) {
                Object d = m.get("date");
                return d == null ? null : d.toString();
            }
        }
        return null;
    }

    private static String pct(double r) { return round(r * 100, 1) + "%"; }
    private static double round(double v) { return round(v, 2); }
    private static double round(double v, int decimals) {
        double s = Math.pow(10, decimals);
        return Math.round(v * s) / s;
    }
}
