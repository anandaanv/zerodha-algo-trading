package com.dtech.aitrader.v2.rules.ew.signature;

import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared primitive that classifies a leg's sub-structure on a given lower timeframe.
 * The PHASE-A bridge discriminator (per SPEC-006 / 1d3e3c25): sub-pivot count + counter retest
 * after partial peak + max intra-leg retrace. PHASE B (pattern-classifier integration) will
 * enrich this with shape labels; the function signature stays.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@code EwWaveCompletionRule} — terminal-wave completeness (SPEC-006).</li>
 *   <li>Every {@code EwSignatureRule} — to compute observed leg characters against signatures
 *       (SPEC reframe 159ba913).</li>
 *   <li>Bigger-impulse precondition checks — examining prior larger-degree A on one TF up.</li>
 * </ul>
 *
 * <p><b>Single source of truth.</b> If the discriminator changes (e.g. PHASE B adds
 * pattern-shape signal), this method is the only place that changes.
 */
public final class LegCharacterExaminer {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Default PHASE-A thresholds. Callers may override per-rule via the signal computer directly. */
    public static final double DEFAULT_COUNTER_RETEST_BAND_PCT = 10.0;
    public static final double DEFAULT_RETRACE_THRESHOLD_PCT = 50.0;
    public static final double DEFAULT_MIN_PARTIAL_MOVE_FRACTION = 0.20;

    private LegCharacterExaminer() {}

    /**
     * Examine a leg span and classify its sub-structure character.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>If fewer than 2 sub-pivots strictly inside the (startDate, endDate) span exist on
     *       {@code preferredSubTf} (with {@code "Day"} fallback): return {@link
     *       LegCharacter#INDETERMINATE}.</li>
     *   <li>If the leg shows counter retest after a partial-peak move AND max intra-leg retrace
     *       ≥ {@code retraceThresholdPct}: classify as {@link LegCharacter#THREE} (corrective —
     *       leg has an internal A-B-C / retest shape).</li>
     *   <li>Otherwise: classify as {@link LegCharacter#FIVE} (impulsive — one-directional push
     *       with shallow internal pullbacks).</li>
     * </ul>
     *
     * <p>The evidence map carries the discriminator signals used in the firing payloads.
     */
    public static Result examine(SymbolContext ctx,
                                   String startDate,
                                   double startPrice,
                                   String endDate,
                                   double endPrice,
                                   String preferredSubTf) {
        return examine(ctx, startDate, startPrice, endDate, endPrice, preferredSubTf,
                DEFAULT_COUNTER_RETEST_BAND_PCT, DEFAULT_RETRACE_THRESHOLD_PCT,
                DEFAULT_MIN_PARTIAL_MOVE_FRACTION);
    }

    public static Result examine(SymbolContext ctx,
                                   String startDate,
                                   double startPrice,
                                   String endDate,
                                   double endPrice,
                                   String preferredSubTf,
                                   double counterRetestBandPct,
                                   double retraceThresholdPct,
                                   double minPartialMoveFraction) {
        if (ctx == null || startDate == null || endDate == null) {
            return new Result(LegCharacter.INDETERMINATE, null, signalsEmpty("missing inputs"));
        }
        List<MarketStructurePoint> sub = subPivotsInSpan(ctx, startDate, endDate, preferredSubTf);
        String subTf = preferredSubTf;
        if (sub == null || sub.isEmpty()) {
            sub = subPivotsInSpan(ctx, startDate, endDate, "Day");
            subTf = "Day";
        }
        if (sub == null || sub.size() < 2) {
            int count = sub == null ? 0 : sub.size();
            return new Result(LegCharacter.INDETERMINATE, subTf,
                    signals(count, false, Double.NaN, 0.0, counterRetestBandPct, retraceThresholdPct,
                            "insufficient sub-pivots (" + count + ") for classification"));
        }

        boolean upLeg = endPrice > startPrice;
        double legHeight = Math.abs(endPrice - startPrice);
        if (legHeight <= 0) {
            return new Result(LegCharacter.INDETERMINATE, subTf,
                    signals(sub.size(), false, Double.NaN, 0.0, counterRetestBandPct, retraceThresholdPct,
                            "leg height is zero"));
        }
        double counterBandAbs = legHeight * (counterRetestBandPct / 100.0);
        double minPartialMoveAbs = legHeight * minPartialMoveFraction;

        boolean counterRetestPass = false;
        double lowestRetestPrice = Double.NaN;
        boolean seenPartialPeakAwayFromStart = false;
        double partialPeak = startPrice;
        double maxRetracePct = 0.0;

        for (MarketStructurePoint p : sub) {
            double price = p.getPrice();
            // Track "have we moved away from start meaningfully yet?"
            if (upLeg && p.getPivotType() == MarketStructurePoint.PivotType.HIGH) {
                if (price - startPrice >= minPartialMoveAbs) seenPartialPeakAwayFromStart = true;
            } else if (!upLeg && p.getPivotType() == MarketStructurePoint.PivotType.LOW) {
                if (startPrice - price >= minPartialMoveAbs) seenPartialPeakAwayFromStart = true;
            }

            // Counter retest check — only counts AFTER partial peak moved away from start.
            boolean isRetestSide = upLeg
                    ? (p.getPivotType() == MarketStructurePoint.PivotType.LOW)
                    : (p.getPivotType() == MarketStructurePoint.PivotType.HIGH);
            if (isRetestSide && seenPartialPeakAwayFromStart) {
                double distance = upLeg ? (price - startPrice) : (startPrice - price);
                if (distance <= counterBandAbs) {
                    counterRetestPass = true;
                    if (Double.isNaN(lowestRetestPrice)
                            || (upLeg ? price < lowestRetestPrice : price > lowestRetestPrice)) {
                        lowestRetestPrice = price;
                    }
                }
            }

            // Max intra-leg retrace.
            if (upLeg) {
                if (p.getPivotType() == MarketStructurePoint.PivotType.HIGH && price > partialPeak) {
                    partialPeak = price;
                } else if (p.getPivotType() == MarketStructurePoint.PivotType.LOW) {
                    double partial = partialPeak - startPrice;
                    if (partial > 0) {
                        double retracePct = (partialPeak - price) / partial * 100.0;
                        if (retracePct > maxRetracePct) maxRetracePct = retracePct;
                    }
                }
            } else {
                if (p.getPivotType() == MarketStructurePoint.PivotType.LOW && price < partialPeak) {
                    partialPeak = price;
                } else if (p.getPivotType() == MarketStructurePoint.PivotType.HIGH) {
                    double partial = startPrice - partialPeak;
                    if (partial > 0) {
                        double retracePct = (price - partialPeak) / partial * 100.0;
                        if (retracePct > maxRetracePct) maxRetracePct = retracePct;
                    }
                }
            }
        }

        // Decision: corrective (THREE) if leg has retest + deep retrace; otherwise impulsive (FIVE).
        boolean corrective = counterRetestPass && maxRetracePct >= retraceThresholdPct;
        LegCharacter character = corrective ? LegCharacter.THREE : LegCharacter.FIVE;
        Map<String, Object> ev = signals(sub.size(), counterRetestPass,
                Double.isNaN(lowestRetestPrice) ? null : lowestRetestPrice,
                maxRetracePct, counterRetestBandPct, retraceThresholdPct,
                corrective
                        ? "leg shows retest after partial peak + ≥" + retraceThresholdPct + "% retrace → 3-wave corrective"
                        : "no retest OR shallow retrace (<" + retraceThresholdPct + "%) → 5-wave impulsive ladder");
        return new Result(character, subTf, ev);
    }

    /** Filter pivots STRICTLY inside the (startDate, endDate) day span, IST. */
    private static List<MarketStructurePoint> subPivotsInSpan(SymbolContext ctx,
                                                                String startDate, String endDate,
                                                                String tf) {
        if (ctx.getPivotsByTf() == null) return null;
        List<MarketStructurePoint> all = ctx.getPivotsByTf().get(tf);
        if (all == null || all.isEmpty()) return null;
        LocalDate startD = LocalDate.parse(startDate);
        LocalDate endD = LocalDate.parse(endDate);
        Instant afterStart = startD.plusDays(1).atStartOfDay(IST).toInstant();
        Instant beforeEnd = endD.atStartOfDay(IST).toInstant();
        List<MarketStructurePoint> out = new ArrayList<>();
        for (MarketStructurePoint p : all) {
            Instant ts = p.getTimestamp();
            if (ts == null) continue;
            if (!ts.isBefore(afterStart) && ts.isBefore(beforeEnd)) out.add(p);
        }
        return out;
    }

    private static Map<String, Object> signals(int subPivotCount, boolean counterRetestPass,
                                                 Double counterRetestLowPrice, double maxRetracePct,
                                                 double bandPct, double thresholdPct, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sub_pivot_count", subPivotCount);
        m.put("counter_retest_pass", counterRetestPass);
        m.put("counter_retest_low_price", counterRetestLowPrice);
        m.put("counter_retest_band_pct_used", bandPct);
        m.put("max_intra_leg_retrace_pct", Math.round(maxRetracePct * 100.0) / 100.0);
        m.put("retrace_threshold_pct_used", thresholdPct);
        m.put("pattern_shape", null);    // PHASE B will populate
        m.put("note", note);
        return m;
    }

    private static Map<String, Object> signalsEmpty(String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("note", note);
        return m;
    }

    /**
     * Examiner result. The {@code subTf} value records which timeframe was actually used (the
     * preferred TF if pivots were available there, otherwise the fallback).
     */
    public record Result(LegCharacter character, String subTf, Map<String, Object> evidence) {
    }
}
