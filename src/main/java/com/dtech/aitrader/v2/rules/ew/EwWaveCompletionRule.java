package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-5 Rule — wave-completeness gate (SPEC-006 / 1d3e3c25). Examines the candidate's
 * terminal-wave interior sub-structure and emits a CONFIRMATION firing carrying a state update
 * for that wave-role on the candidate's {@code pivot_assignment}. Pass-6's
 * {@link com.dtech.aitrader.v2.rules.synthesis.EwVerdictSynthesisRule} reads the latest state
 * firing per terminal role and gates VERDICT emission on it.
 *
 * <p>This rule is DISTINCT from {@link EwLegSubstructureRule}: the latter examines the leg
 * AFTER the terminal pivot (the in-progress C or W2), confirming its direction; THIS rule
 * examines the leg LEADING TO the terminal pivot to decide whether the candidate B_end /
 * W1_end is itself complete or still in progress.
 *
 * <p><b>PHASE A discriminator (provisional, per SPEC-006 MOD-1):</b> a bridge until the pattern
 * family's impulsive/corrective shape classifier lands. Two signals on Hr sub-pivots inside the
 * leg span {@code (start, end)} (start = A_end for zigzag B / W0 for impulse W1; end = the
 * CANDIDATE B_end / W1_end):
 *
 * <ol>
 *   <li><b>Counter retest:</b> at least one Hr LOW (for zigzag B) / HIGH (for impulse W1)
 *       inside the span reaches within {@code counter_retest_band} (default 10%) of the start
 *       price. A 100% retest is a clear "B.B reached A's terminus" signal — the leg has an
 *       internal A-B-C shape.</li>
 *   <li><b>Max intra-leg retracement:</b> the deepest counter-trend pullback inside the leg as a
 *       fraction of the partial-leg height up to that pullback. ≥ {@code retrace_threshold}
 *       (default 50%) indicates a structural retracement consistent with a completed corrective
 *       A-B-C; below indicates a one-directional impulsive ladder (B.A only).</li>
 * </ol>
 *
 * <p>Decision matrix:
 * <pre>
 *   zigzag B (should be CORRECTIVE if complete):
 *     counter_retest AND max_retrace ≥ threshold → COMPLETE
 *     otherwise                                  → IN_PROGRESS
 *   impulse W1 (should be IMPULSIVE if complete):
 *     NOT counter_retest AND max_retrace < threshold → COMPLETE  (clean 5-wave impulse)
 *     otherwise                                       → IN_PROGRESS
 * </pre>
 *
 * <p>RELIANCE (cde6bbc9) blessed: Hr leg 1290→1473.4 retests 1290 (100% counter retest) AND has
 * full intra-leg retrace → B = COMPLETE. ICICIBANK (e409cb9e) blessed: Hr leg 1187.6→1393.1
 * deepest pullback is 1275.9 (7.4% above counter, no retest) AND retrace ~40% &lt; 50% → B =
 * IN_PROGRESS (only B.A formed).
 *
 * <p>All firings carry {@code provisional: true} in their payload — this discriminator is the
 * PHASE A bridge per SPEC-006 MOD-1. PHASE B replaces it with the pattern-shape classifier as
 * the primary signal; retest demotes to a corroborator.
 */
@Component
@Slf4j
public class EwWaveCompletionRule implements Rule {

    public static final String RULE_ID = "EW_WAVE_COMPLETION";

    @Value("${rules.ew.completion.counter-retest-band-pct:10.0}")
    private double counterRetestBandPct = 10.0;

    @Value("${rules.ew.completion.retrace-threshold-pct:50.0}")
    private double retraceThresholdPct = 50.0;

    @Value("${rules.ew.completion.complete-delta:0.03}")
    private double completeDelta = 0.03;

    @Value("${rules.ew.completion.in-progress-delta:-0.10}")
    private double inProgressDelta = -0.10;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P5_CONFIRMATION; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        List<Firing> candidates = priorFirings.stream()
                .filter(f -> f.getFamily() == Family.EW)
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .toList();
        if (candidates.isEmpty()) return List.of();

        java.util.Set<String> eliminated = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.ELIMINATION && f.getRefs() != null)
                .flatMap(f -> f.getRefs().stream())
                .collect(java.util.stream.Collectors.toSet());

        List<Firing> out = new ArrayList<>();
        for (Firing cand : candidates) {
            if (eliminated.contains(cand.getId())) continue;
            Firing fired = examine(ctx, cand);
            if (fired != null) out.add(fired);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Firing examine(SymbolContext ctx, Firing candidate) {
        String form = (String) candidate.getPayload().get("form");
        List<Map<String, Object>> assignment =
                (List<Map<String, Object>>) candidate.getPayload().get("pivot_assignment");
        if (assignment == null || form == null) return null;

        // Resolve terminal wave role + span endpoints per form.
        String terminalRole;
        String startRole;
        boolean expectCorrective;  // true for zigzag B (should be corrective if complete);
                                    // false for impulse W1 (should be impulsive if complete)
        if ("zigzag".equals(form)) {
            terminalRole = "B_end";
            startRole = "A_end";
            expectCorrective = true;
        } else if ("impulse".equals(form)) {
            terminalRole = "W1_end";
            startRole = "W0";
            expectCorrective = false;
        } else {
            return null;
        }

        // Only act on CANDIDATE roles (innocent-until-proven-complete). If state is already
        // resolved (COMPLETE / IN_PROGRESS by an earlier firing in this round), skip — Pass-6
        // walks the chain to find the latest state anyway.
        Map<String, Object> terminalEntry = roleEntry(assignment, terminalRole);
        if (terminalEntry == null) return null;
        String currentState = (String) terminalEntry.get("state");
        if (currentState != null
                && !EwEnumerationRule.STATE_CANDIDATE.equals(currentState)) {
            return null;
        }

        Double startPrice = priceOf(assignment, startRole);
        String startDate = dateOf(assignment, startRole);
        Double endPrice = priceOf(assignment, terminalRole);
        String endDate = dateOf(assignment, terminalRole);
        if (startPrice == null || startDate == null || endPrice == null || endDate == null) return null;

        // Get Hr sub-pivots strictly within the leg span (Day fallback if Hr absent).
        List<MarketStructurePoint> sub = subPivotsInSpan(ctx, startDate, endDate, "OneHour");
        String subTf = "OneHour";
        if (sub == null || sub.isEmpty()) {
            sub = subPivotsInSpan(ctx, startDate, endDate, "Day");
            subTf = "Day";
        }
        if (sub == null || sub.size() < 2) return null;   // indeterminate

        // Discriminator signals.
        DiscriminatorSignals signals = computeSignals(sub, startPrice, endPrice, expectCorrective);

        // Decision: zigzag B wants corrective shape; impulse W1 wants impulsive shape.
        boolean isComplete;
        if (expectCorrective) {
            isComplete = signals.counterRetestPass && signals.maxRetracePct >= retraceThresholdPct;
        } else {
            // Impulse W1 complete = clean 5-wave-ish impulse: NO counter retest, retraces shallow.
            isComplete = !signals.counterRetestPass && signals.maxRetracePct < retraceThresholdPct;
        }
        String newState = isComplete
                ? EwEnumerationRule.STATE_COMPLETE
                : EwEnumerationRule.STATE_IN_PROGRESS;
        double delta = isComplete ? completeDelta : inProgressDelta;

        Map<String, Object> discriminatorSignals = new LinkedHashMap<>();
        discriminatorSignals.put("sub_tf", subTf);
        discriminatorSignals.put("sub_pivot_count", sub.size());
        discriminatorSignals.put("counter_retest_pass", signals.counterRetestPass);
        discriminatorSignals.put("counter_retest_low_price", signals.lowestRetestPrice);
        discriminatorSignals.put("counter_retest_band_pct_used", counterRetestBandPct);
        discriminatorSignals.put("max_intra_leg_retrace_pct", round(signals.maxRetracePct));
        discriminatorSignals.put("retrace_threshold_pct_used", retraceThresholdPct);
        discriminatorSignals.put("pattern_shape", null);  // PHASE B will populate

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", terminalRole);
        payload.put("new_state", newState);
        payload.put("candidate_form", form);
        payload.put("discriminator_signals", discriminatorSignals);
        payload.put("reasoning", buildReasoning(expectCorrective, isComplete, signals, subTf));
        payload.put("provisional", true);
        payload.put("provisional_note",
                "discriminator = sub-pivot-count + retest; pattern-shape confluence pending pattern-classifier (SPEC-006 PHASE B)");

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P5_CONFIRMATION)
                .firesOn(FiresOn.CONFIRMATION)
                .refs(List.of(candidate.getId()))
                .priorDelta(PriorDelta.graduated(delta, payload.get("reasoning").toString(), "SPEC-006"))
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }

    private DiscriminatorSignals computeSignals(List<MarketStructurePoint> sub,
                                                  double startPrice, double endPrice,
                                                  boolean expectCorrective) {
        // For zigzag B (up-leg in a downside zigzag): start is the LOW (A_end / counter), end is the
        // HIGH (B_end). Counter retest = some intermediate Hr LOW within band% of startPrice.
        // For impulse W1 (up-leg): start = W0 LOW, end = W1_end HIGH. Same retest semantics.
        // (For a mirror upside zigzag — A_start LOW → A_end HIGH → B down — the orientation flips;
        // we treat all forms here uniformly via |start-end| and check both extremes.)
        boolean upLeg = endPrice > startPrice;
        double legHeight = Math.abs(endPrice - startPrice);
        if (legHeight <= 0) {
            return new DiscriminatorSignals(false, Double.NaN, 0.0);
        }
        double counterBandAbs = legHeight * (counterRetestBandPct / 100.0);

        // Counter retest = the leg first moves AWAY from start, then comes BACK to within band.
        // If the very first sub-pivot is at/near start, it's just the leg's beginning — not a
        // retest. We require at least one prior partial-peak that moved meaningfully away from
        // start before counting any subsequent return as a retest.
        // For an up-leg (zigzag B / impulse W1): retest = a LOW close to start, AFTER a HIGH
        // pivot above start has formed. For a down-leg (mirror): retest = a HIGH close to start
        // after a LOW below start has formed.
        boolean counterRetestPass = false;
        double lowestRetestPrice = Double.NaN;
        boolean seenPartialPeakAwayFromStart = false;
        double minPartialMoveAbs = legHeight * 0.20;   // at least 20% of total leg height move away
        for (MarketStructurePoint p : sub) {
            double price = p.getPrice();
            // Update "have we moved away from start meaningfully yet?"
            if (upLeg && p.getPivotType() == MarketStructurePoint.PivotType.HIGH) {
                if (price - startPrice >= minPartialMoveAbs) seenPartialPeakAwayFromStart = true;
            } else if (!upLeg && p.getPivotType() == MarketStructurePoint.PivotType.LOW) {
                if (startPrice - price >= minPartialMoveAbs) seenPartialPeakAwayFromStart = true;
            }
            // Retest check (on the start side only, AND only after we've moved away).
            boolean isRetestSide = upLeg
                    ? (p.getPivotType() == MarketStructurePoint.PivotType.LOW)
                    : (p.getPivotType() == MarketStructurePoint.PivotType.HIGH);
            if (!isRetestSide) continue;
            if (!seenPartialPeakAwayFromStart) continue;   // pre-move LOW/HIGH is leg start, not a retest
            double distanceFromStart = upLeg ? (price - startPrice) : (startPrice - price);
            if (distanceFromStart <= counterBandAbs) {
                counterRetestPass = true;
                if (Double.isNaN(lowestRetestPrice)
                        || (upLeg ? price < lowestRetestPrice : price > lowestRetestPrice)) {
                    lowestRetestPrice = price;
                }
            }
        }

        // Max intra-leg retracement: scan sub-pivots in order; track running peak (high water mark)
        // of the partial leg, find the deepest counter-trend pullback as fraction of partial-leg
        // height at that point.
        // For an up-leg: peak = max HIGH so far from start; pullback = (peak - subsequent LOW) /
        // (peak - startPrice).
        double maxRetracePct = 0.0;
        double partialPeak = startPrice;
        for (MarketStructurePoint p : sub) {
            double price = p.getPrice();
            if (upLeg) {
                if (p.getPivotType() == MarketStructurePoint.PivotType.HIGH && price > partialPeak) {
                    partialPeak = price;
                } else if (p.getPivotType() == MarketStructurePoint.PivotType.LOW) {
                    double partial = partialPeak - startPrice;
                    if (partial > 0) {
                        double pullback = partialPeak - price;
                        double retracePct = (pullback / partial) * 100.0;
                        if (retracePct > maxRetracePct) maxRetracePct = retracePct;
                    }
                }
            } else {
                if (p.getPivotType() == MarketStructurePoint.PivotType.LOW && price < partialPeak) {
                    partialPeak = price;
                } else if (p.getPivotType() == MarketStructurePoint.PivotType.HIGH) {
                    double partial = startPrice - partialPeak;
                    if (partial > 0) {
                        double pullback = price - partialPeak;
                        double retracePct = (pullback / partial) * 100.0;
                        if (retracePct > maxRetracePct) maxRetracePct = retracePct;
                    }
                }
            }
        }

        return new DiscriminatorSignals(counterRetestPass, lowestRetestPrice, maxRetracePct);
    }

    private String buildReasoning(boolean expectCorrective, boolean isComplete,
                                    DiscriminatorSignals signals, String subTf) {
        String shape = expectCorrective ? "zigzag-B (wants corrective A-B-C)"
                                          : "impulse-W1 (wants impulsive 5-wave)";
        return String.format("PHASE-A discriminator on %s: sub_tf=%s, counter_retest=%s, max_retrace=%.1f%% (threshold %.1f%%)"
                        + " → %s. Provisional pending pattern-shape classifier (SPEC-006 PHASE B).",
                shape, subTf,
                signals.counterRetestPass,
                signals.maxRetracePct,
                retraceThresholdPct,
                isComplete ? "COMPLETE" : "IN_PROGRESS");
    }

    private List<MarketStructurePoint> subPivotsInSpan(SymbolContext ctx, String startDate,
                                                         String endDate, String tf) {
        if (ctx.getPivotsByTf() == null) return null;
        List<MarketStructurePoint> all = ctx.getPivotsByTf().get(tf);
        if (all == null || all.isEmpty()) return null;
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        // Strictly STRICTLY-INSIDE the (startDate, endDate) span — exclude pivots ON the start
        // date (so the counter pivot itself doesn't register as its own retest) and BEFORE the
        // end date (so the terminal pivot itself isn't double-counted).
        LocalDate startD = LocalDate.parse(startDate);
        LocalDate endD = LocalDate.parse(endDate);
        Instant afterStart = startD.plusDays(1).atStartOfDay(ist).toInstant();
        Instant beforeEnd = endD.atStartOfDay(ist).toInstant();
        List<MarketStructurePoint> out = new ArrayList<>();
        for (MarketStructurePoint p : all) {
            Instant ts = p.getTimestamp();
            if (ts == null) continue;
            // ts >= afterStart (i.e. date >= startDate + 1) AND ts < beforeEnd (date < endDate)
            if (!ts.isBefore(afterStart) && ts.isBefore(beforeEnd)) {
                out.add(p);
            }
        }
        return out;
    }

    private static Map<String, Object> roleEntry(List<Map<String, Object>> assignment, String role) {
        for (Map<String, Object> m : assignment) {
            if (role.equals(m.get("role"))) return m;
        }
        return null;
    }

    private static Double priceOf(List<Map<String, Object>> assignment, String role) {
        Map<String, Object> e = roleEntry(assignment, role);
        if (e == null) return null;
        Object p = e.get("price");
        if (p instanceof Number n) return n.doubleValue();
        return null;
    }

    private static String dateOf(List<Map<String, Object>> assignment, String role) {
        Map<String, Object> e = roleEntry(assignment, role);
        if (e == null) return null;
        Object d = e.get("date");
        return d == null ? null : d.toString();
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    /** Bag of discriminator signal values + intermediate retest data. */
    private record DiscriminatorSignals(boolean counterRetestPass,
                                          double lowestRetestPrice,
                                          double maxRetracePct) {}
}
