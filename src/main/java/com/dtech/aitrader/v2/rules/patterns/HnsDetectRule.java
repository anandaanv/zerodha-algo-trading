package com.dtech.aitrader.v2.rules.patterns;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-2 candidate emitter for the bearish Head &amp; Shoulders pattern per SPEC-008
 * ({@code e332be7f}) with completion measured CONTINUOUSLY per owner correction
 * ({@code 89a52589}).
 *
 * <p>completion_pct is derived from H&amp;S's OWN geometry — NOT a hardcoded ladder. Each
 * geometric piece (L-shoulder, trough1, head, rollover depth, trough2 + symmetry, R-shoulder
 * + symmetry, distance-to-neckline, confirmed break) contributes a continuous fraction to a
 * total in [0, 100].
 *
 * <p>Backbone gate: L-shoulder + trough1 + head (head ≥ L-shoulder + 1×ATR) + correct ordering.
 * If the backbone fails, completion = 0 — no firing. Otherwise the formula is:
 * <pre>
 *   backbone established                                : +20
 *   rollover (close depth below head, toward trough1)   : +[0, 25]
 *   trough2 present                                     : +10
 *   trough2 ↔ trough1 symmetry                          : +[0, 5]
 *   R-shoulder present                                  : +10
 *   R-shoulder ↔ L-shoulder symmetry                    : +[0, 5]
 *   distance from close to neckline (when R present)    : +[0, 10]
 *   confirmed neckline break                            : clamps to ≥ 85 then +15 → 100
 * </pre>
 *
 * <p>Owner spec: "completion measure falls out of that geometry, not a shared ladder."
 * status="confirmed" iff completion ≥ 95; status="forming" otherwise. Forming firings emit
 * only when completion ≥ {@value #EMISSION_THRESHOLD}.
 */
@Component
@Slf4j
public class HnsDetectRule implements Rule {

    public static final String RULE_ID = "HNS_DETECT";

    private static final int ATR_PERIOD = 14;
    private static final double SHOULDER_EQUAL_TOL_ATR = 1.0;
    private static final double HEAD_HEIGHT_MIN_ATR = 1.0;
    private static final double TROUGH_EQUAL_TOL_ATR = 1.5;
    private static final int MIN_LEG_BARS = 3;
    private static final int MAX_LEG_BARS = 50;
    private static final double BASE_PRIOR = 0.40;
    /**
     * Maximum span (in bars) of the full L-shoulder-to-R-shoulder structure. H&amp;S can span
     * months at higher degrees per owner direction {@code 474986f0} — wider scan needed than the
     * old MAX_LEG_BARS×4 cap that rejected multi-month patterns.
     */
    private static final int MAX_PATTERN_SPAN_BARS = 250;
    /**
     * Shoulder asymmetry: base tolerance is 3% of head price; scales up to +4% for long-span
     * patterns per owner direction {@code 474986f0} ("span-scaled shoulder asymmetry tolerance —
     * large H&amp;S tolerates proportionally more asymmetry"). At 110-bar span (~6 months daily)
     * scaling reaches the full +4% → 7% total tolerance, which covers RELIANCE-scale 5-6%
     * asymmetry between Nov 2025 L-shoulder and May 2026 R-shoulder.
     */
    private static final double BASE_SHOULDER_ASYM_PCT = 0.03;
    private static final double SPAN_SHOULDER_ASYM_PCT = 0.04;
    private static final int SPAN_SCALE_MIN_BARS = 10;
    private static final int SPAN_SCALE_MAX_BARS = 110;
    /**
     * Shoulder-depth gate (owner correction {@code fbabb0b9}): shoulders must rise at least 23%
     * of the head's height above the neckline. Textbook says ~50%; shallow-shouldered H&amp;S
     * (well-symmetric, just flat-topped) at 17-23% are valid but were rejected by tighter gates.
     * Below this floor the pattern is too flat to read as H&amp;S; above it the strength score
     * scales from 0 (at 0.23) to full bonus (at 0.50 = textbook).
     */
    private static final double MIN_SHOULDER_DEPTH_RATIO = 0.23;
    private static final double TEXTBOOK_SHOULDER_DEPTH_RATIO = 0.50;

    /** Below this, the geometry is too weak to count as forming — emit nothing. */
    private static final double EMISSION_THRESHOLD = 25.0;
    private static final double CONFIRMED_THRESHOLD = 95.0;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P2_ENUMERATION; }
    @Override public Family family() { return Family.PATTERN; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        BarSeries series = ctx.getSeries();
        List<MarketStructurePoint> pivots = ctx.getPivots();
        if (series == null || pivots == null || pivots.size() < 3) return List.of();
        int endIdx = series.getEndIndex();
        if (endIdx < 1) return List.of();

        ATRIndicator atrInd = new ATRIndicator(series, ATR_PERIOD);
        double atr = atrInd.getValue(endIdx).doubleValue();
        if (atr <= 0) return List.of();

        Map<Instant, Integer> indexer = indexer(series);
        List<PivotRef> allHighs = sortedPivots(pivots, indexer, PivotType.HIGH);
        List<PivotRef> allLows = sortedPivots(pivots, indexer, PivotType.LOW);
        if (allHighs.isEmpty()) return List.of();

        double closeNow = series.getBar(endIdx).getClosePrice().doubleValue();
        double closePrev = series.getBar(endIdx - 1).getClosePrice().doubleValue();

        // Owner direction 474986f0: scan ALL H-L-H-L-H triples in the lookback, emit EVERY valid
        // H&S — not "latest-3, take-biggest". Multiple co-live patterns at different degrees can
        // be detected and surface as separate annotations on the EW context.
        List<Firing> out = new ArrayList<>();
        java.util.Set<Integer> emittedHeads = new java.util.HashSet<>();

        for (int li = 0; li < allHighs.size() - 2; li++) {
            PivotRef L = allHighs.get(li);
            for (int hi = li + 1; hi < allHighs.size() - 1; hi++) {
                PivotRef H = allHighs.get(hi);
                if (H.idx - L.idx < MIN_LEG_BARS) continue;
                for (int ri = hi + 1; ri < allHighs.size(); ri++) {
                    PivotRef R = allHighs.get(ri);
                    if (R.idx - H.idx < MIN_LEG_BARS) continue;
                    if (R.idx - L.idx > MAX_PATTERN_SPAN_BARS) continue;
                    PivotRef t1 = findLowBetween(allLows, L.idx, H.idx);
                    PivotRef t2 = findLowBetween(allLows, H.idx, R.idx);
                    if (t1 == null || t2 == null) continue;
                    Optional5Pivot window = new Optional5Pivot(L, t1, H, t2, R);
                    if (!passesBackboneGeometry(window, atr)) continue;

                    double neckline = necklineAt(window, endIdx);
                    boolean broke = closePrev >= neckline && closeNow < neckline;
                    boolean alreadyBelowNeckline = closeNow < neckline;
                    boolean confirmedBreak = broke || alreadyBelowNeckline;
                    double completion = computeCompletion(window, atr, closeNow, neckline, confirmedBreak);
                    if (completion < EMISSION_THRESHOLD) continue;

                    // Dedup at head bar idx — multiple (L, R) pairs around the same head produce
                    // overlapping patterns; emit the FIRST encountered (widest left + first right).
                    if (!emittedHeads.add(H.idx)) continue;

                    String status = completion >= CONFIRMED_THRESHOLD ? "confirmed" : "forming";
                    List<String> earlySigns = collectEarlySigns(window, closeNow, neckline, confirmedBreak);
                    out.add(buildFiring(ctx, window, neckline, atr, endIdx, closeNow,
                            status, completion, earlySigns));
                }
            }
        }

        // Partial 2-window: head=latest HIGH that wasn't part of any full window. Only fires for
        // a still-forming HnS where R-shoulder hasn't formed yet.
        if (allHighs.size() >= 2) {
            PivotRef L = allHighs.get(allHighs.size() - 2);
            PivotRef H = allHighs.get(allHighs.size() - 1);
            if (H.idx - L.idx >= MIN_LEG_BARS && !emittedHeads.contains(H.idx)) {
                PivotRef t1 = findLowBetween(allLows, L.idx, H.idx);
                if (t1 != null) {
                    Optional5Pivot partial = new Optional5Pivot(L, t1, H, null, null);
                    if (passesBackbonePartial(partial, atr)) {
                        double neckline = t1.price;
                        double completion = computeCompletion(partial, atr, closeNow, neckline, false);
                        if (completion >= EMISSION_THRESHOLD) {
                            List<String> earlySigns = collectEarlySigns(partial, closeNow, neckline, false);
                            out.add(buildFiring(ctx, partial, neckline, atr, endIdx, closeNow,
                                    "forming", completion, earlySigns));
                        }
                    }
                }
            }
        }

        return out;
    }

    private static PivotRef findLowBetween(List<PivotRef> lows, int leftIdx, int rightIdx) {
        // Find a LOW pivot strictly between two HIGH bar indices. If multiple lows exist,
        // pick the LOWEST-priced (canonical neckline anchor).
        PivotRef best = null;
        for (PivotRef p : lows) {
            if (p.idx <= leftIdx || p.idx >= rightIdx) continue;
            if (best == null || p.price < best.price) best = p;
        }
        return best;
    }

    private static List<PivotRef> sortedPivots(List<MarketStructurePoint> pivots,
                                                  Map<Instant, Integer> indexer, PivotType type) {
        List<PivotRef> collected = new ArrayList<>();
        for (MarketStructurePoint p : pivots) {
            if (p.getPivotType() != type) continue;
            Integer idx = indexer.get(p.getTimestamp());
            if (idx == null) continue;
            collected.add(new PivotRef(idx, p.getPrice(), p.getTimestamp()));
        }
        collected.sort((a, b) -> Integer.compare(a.idx, b.idx));
        return collected;
    }

    /**
     * Continuous completion in [0, 100]. Returns 0 if backbone gate fails (no forming).
     * Each piece is additive; total clamped to 100.
     */
    private double computeCompletion(Optional5Pivot w, double atr, double closeNow,
                                       double neckline, boolean confirmedBreak) {
        if (w.lShoulder == null || w.trough1 == null || w.head == null) return 0.0;
        // Backbone geometric checks: head must clear L-shoulder by ≥1 ATR and ordering must hold.
        if (w.head.price < w.lShoulder.price + HEAD_HEIGHT_MIN_ATR * atr) return 0.0;
        if (!(w.lShoulder.idx < w.trough1.idx && w.trough1.idx < w.head.idx)) return 0.0;

        double c = 20.0;  // backbone established

        // Rollover: how far has close come back from head toward trough1? Continuous [0, 25].
        double rolloverRange = Math.max(1e-9, w.head.price - w.trough1.price);
        double rolloverFrac = clamp01((w.head.price - closeNow) / rolloverRange);
        c += 25.0 * rolloverFrac;

        if (w.trough2 != null) {
            c += 10.0;
            double troughErr = Math.abs(w.trough1.price - w.trough2.price)
                    / Math.max(1e-9, atr * TROUGH_EQUAL_TOL_ATR);
            c += 5.0 * clamp01(1.0 - troughErr);
        }

        if (w.rShoulder != null) {
            c += 10.0;
            double shoulderErr = Math.abs(w.lShoulder.price - w.rShoulder.price)
                    / Math.max(1e-9, atr * SHOULDER_EQUAL_TOL_ATR);
            c += 5.0 * clamp01(1.0 - shoulderErr);

            // Distance-to-neckline (price approaching the trigger): continuous [0, 10].
            if (neckline > 0 && w.rShoulder.price > neckline && closeNow > neckline) {
                double approachRange = Math.max(1e-9, w.rShoulder.price - neckline);
                double approachFrac = clamp01(1.0 - (closeNow - neckline) / approachRange);
                c += 10.0 * approachFrac;
            }
        }

        // Owner correction fbabb0b9: depth quality score [0, 5]. Backbone gate already rejected
        // ratios < 0.23; here we reward higher ratios up to the textbook 0.50 = full quality.
        // Shallow-shouldered patterns still fire but score lower; textbook depth tops it out.
        double depthRatio = (w.trough2 != null) ? shoulderDepthRatioFull(w) : shoulderDepthRatioPartial(w);
        double depthRange = TEXTBOOK_SHOULDER_DEPTH_RATIO - MIN_SHOULDER_DEPTH_RATIO;
        double depthQuality = clamp01((depthRatio - MIN_SHOULDER_DEPTH_RATIO) / depthRange);
        c += 5.0 * depthQuality;

        if (confirmedBreak) {
            c = Math.max(c, 85.0) + 15.0;
        }
        return Math.min(100.0, c);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private boolean passesBackboneGeometry(Optional5Pivot w, double atr) {
        // Span-scaled shoulder asymmetry (owner 474986f0): tolerance is 3-7% of head price,
        // larger for long-span patterns. Replaces the old ATR-tight equality which rejected
        // large-degree H&S with naturally-asymmetric shoulders.
        if (!shouldersSufficientlyEqual(w)) return false;
        // Head must be HIGHEST and meaningfully above both shoulders.
        if (w.head.price < Math.max(w.lShoulder.price, w.rShoulder.price) + HEAD_HEIGHT_MIN_ATR * atr) return false;
        if (Math.abs(w.trough1.price - w.trough2.price) > TROUGH_EQUAL_TOL_ATR * atr) return false;
        // Sloped-neckline check (owner 43493549): shoulders must rise ABOVE the neckline
        // extrapolated to their bar idx — NOT a horizontal "both troughs below both shoulders"
        // gate, which rejects steeply-sloped necklines like the Nov-Feb RELIANCE structure
        // (trough 1517 → 1335, falling).
        double necklineAtL = necklineAt(w, w.lShoulder.idx);
        double necklineAtR = necklineAt(w, w.rShoulder.idx);
        double necklineAtHead = necklineAt(w, w.head.idx);
        if (w.lShoulder.price <= necklineAtL) return false;
        if (w.rShoulder.price <= necklineAtR) return false;
        if (w.head.price <= necklineAtHead) return false;
        if (!(w.lShoulder.idx < w.trough1.idx && w.trough1.idx < w.head.idx
                && w.head.idx < w.trough2.idx && w.trough2.idx < w.rShoulder.idx)) return false;
        if (!legSpansValid(w.lShoulder, w.trough1, w.head, w.trough2, w.rShoulder)) return false;
        if (shoulderDepthRatioFull(w) < MIN_SHOULDER_DEPTH_RATIO) return false;
        return true;
    }

    /**
     * Owner direction {@code 474986f0}: shoulder asymmetry tolerated as a percentage of head
     * price, scaled by leg span — short patterns get tight 3%, long patterns up to 7%.
     */
    private boolean shouldersSufficientlyEqual(Optional5Pivot w) {
        double asymPct = Math.abs(w.lShoulder.price - w.rShoulder.price) / w.head.price;
        int legBars = w.rShoulder.idx - w.lShoulder.idx;
        double spanFactor = clamp01((legBars - SPAN_SCALE_MIN_BARS)
                / (double) (SPAN_SCALE_MAX_BARS - SPAN_SCALE_MIN_BARS));
        double tolerancePct = BASE_SHOULDER_ASYM_PCT + SPAN_SHOULDER_ASYM_PCT * spanFactor;
        return asymPct <= tolerancePct;
    }

    private boolean passesBackbonePartial(Optional5Pivot w, double atr) {
        // 2-window backbone: L + trough1 + head only. Head must clear L by ≥1 ATR.
        if (w.head.price < w.lShoulder.price + HEAD_HEIGHT_MIN_ATR * atr) return false;
        if (!(w.lShoulder.idx < w.trough1.idx && w.trough1.idx < w.head.idx)) return false;
        if (!legSpansValid(w.lShoulder, w.trough1, w.head)) return false;
        if (shoulderDepthRatioPartial(w) < MIN_SHOULDER_DEPTH_RATIO) return false;
        return true;
    }

    /**
     * Shoulder-depth ratio for the full 5-pivot pattern. Owner gate {@code fbabb0b9}: the
     * SHALLOWER shoulder above the HIGHER neckline trough, divided by head height above the same
     * trough. Returns the ratio (0 = flat, 1 = shoulder-as-tall-as-head). Conservative — uses
     * max(trough1, trough2) as the neckline proxy so even sloped necklines don't artificially
     * inflate the ratio.
     */
    private static double shoulderDepthRatioFull(Optional5Pivot w) {
        double minShoulder = Math.min(w.lShoulder.price, w.rShoulder.price);
        double necklineProxy = Math.max(w.trough1.price, w.trough2.price);
        double headHeight = w.head.price - necklineProxy;
        double shoulderHeight = minShoulder - necklineProxy;
        if (headHeight <= 0) return 0.0;
        return shoulderHeight / headHeight;
    }

    private static double shoulderDepthRatioPartial(Optional5Pivot w) {
        // 2-window has only trough1 as neckline reference.
        double headHeight = w.head.price - w.trough1.price;
        double shoulderHeight = w.lShoulder.price - w.trough1.price;
        if (headHeight <= 0) return 0.0;
        return shoulderHeight / headHeight;
    }

    private boolean legSpansValid(PivotRef... pivots) {
        for (int i = 1; i < pivots.length; i++) {
            int span = pivots[i].idx - pivots[i - 1].idx;
            if (span < MIN_LEG_BARS || span > MAX_LEG_BARS) return false;
        }
        return true;
    }

    private static double necklineAt(Optional5Pivot w, int atIdx) {
        double dx = w.trough2.idx - w.trough1.idx;
        if (dx == 0) return w.trough1.price;
        double slope = (w.trough2.price - w.trough1.price) / dx;
        return w.trough1.price + slope * (atIdx - w.trough1.idx);
    }

    private static Optional5Pivot tryFiveWindow(List<PivotRef> highs, List<PivotRef> lows) {
        if (highs.size() < 3 || lows.size() < 2) return null;
        PivotRef l = highs.get(highs.size() - 3);
        PivotRef h = highs.get(highs.size() - 2);
        PivotRef r = highs.get(highs.size() - 1);
        PivotRef t1 = lows.get(lows.size() - 2);
        PivotRef t2 = lows.get(lows.size() - 1);
        return new Optional5Pivot(l, t1, h, t2, r);
    }

    private static Optional5Pivot buildPartialWindow(List<PivotRef> highs, List<PivotRef> lows) {
        if (highs.size() < 2 || lows.isEmpty()) return null;
        PivotRef l = highs.get(0);
        PivotRef h = highs.get(1);
        PivotRef t1 = lows.get(lows.size() - 1);
        // trough1 must sit between L-shoulder and head index.
        if (!(t1.idx > l.idx && t1.idx < h.idx)) return null;
        return new Optional5Pivot(l, t1, h, null, null);
    }

    private static List<PivotRef> recentPivots(List<MarketStructurePoint> pivots,
                                                 Map<Instant, Integer> indexer, PivotType type,
                                                 int limit) {
        List<PivotRef> collected = new ArrayList<>();
        for (MarketStructurePoint p : pivots) {
            if (p.getPivotType() != type) continue;
            Integer idx = indexer.get(p.getTimestamp());
            if (idx == null) continue;
            collected.add(new PivotRef(idx, p.getPrice(), p.getTimestamp()));
        }
        collected.sort((a, b) -> Integer.compare(a.idx, b.idx));
        if (collected.size() <= limit) return collected;
        return collected.subList(collected.size() - limit, collected.size());
    }

    private static Map<Instant, Integer> indexer(BarSeries series) {
        Map<Instant, Integer> m = new HashMap<>(series.getBarCount() * 2);
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            m.put(series.getBar(i).getEndTime(), i);
        }
        return m;
    }

    /** Collect factual evidence-flags describing what's currently visible. */
    private List<String> collectEarlySigns(Optional5Pivot w, double closeNow, double neckline,
                                             boolean confirmedBreak) {
        List<String> signs = new ArrayList<>();
        signs.add("head_higher_than_l_shoulder_by_at_least_1_atr");
        if (closeNow < w.head.price) signs.add("post_head_rollover_started");
        if (w.trough2 == null) signs.add("first_lower_high_pending");
        if (w.rShoulder != null) signs.add("right_shoulder_built");
        if (w.rShoulder != null && !confirmedBreak) signs.add("neckline_unbroken");
        if (confirmedBreak) signs.add("neckline_broken");
        return signs;
    }

    private Firing buildFiring(SymbolContext ctx, Optional5Pivot w, double neckline, double atr,
                                 int endIdx, double closeNow, String status, double completion,
                                 List<String> earlySigns) {
        double necklineAtHead = (w.trough2 != null)
                ? necklineAt(w, w.head.idx)
                : w.trough1.price;
        double headHeight = w.head.price - necklineAtHead;
        double targetPrice = neckline - headHeight;
        double invalidationPrice = w.head.price + 0.5 * atr;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        // completion_pct is a CONTINUOUS double in [0, 100] — owner 89a52589.
        payload.put("completion_pct", completion);
        payload.put("early_signs", earlySigns);
        payload.put("bias", "SHORT");
        payload.put("l_shoulder_idx", w.lShoulder.idx);
        payload.put("l_shoulder_price", w.lShoulder.price);
        payload.put("head_idx", w.head.idx);
        payload.put("head_price", w.head.price);
        if (w.rShoulder != null) {
            payload.put("r_shoulder_idx", w.rShoulder.idx);
            payload.put("r_shoulder_price", w.rShoulder.price);
        }
        payload.put("trough1_idx", w.trough1.idx);
        payload.put("trough1_price", w.trough1.price);
        if (w.trough2 != null) {
            payload.put("trough2_idx", w.trough2.idx);
            payload.put("trough2_price", w.trough2.price);
        }
        payload.put("neckline_price", neckline);
        payload.put("head_height", headHeight);
        payload.put("atr_at_detection", atr);
        payload.put("trigger_price", neckline);
        payload.put("invalidation_price", invalidationPrice);
        payload.put("target_price", targetPrice);
        payload.put("current_close", closeNow);

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.PATTERN)
                .pass(Pass.P2_ENUMERATION)
                .firesOn(FiresOn.CANDIDATE)
                .basePrior(BASE_PRIOR)
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }

    record PivotRef(int idx, double price, Instant ts) { }

    static final class Optional5Pivot {
        final PivotRef lShoulder;
        final PivotRef trough1;
        final PivotRef head;
        final PivotRef trough2;
        final PivotRef rShoulder;

        Optional5Pivot(PivotRef lShoulder, PivotRef trough1, PivotRef head,
                         PivotRef trough2, PivotRef rShoulder) {
            this.lShoulder = lShoulder;
            this.trough1 = trough1;
            this.head = head;
            this.trough2 = trough2;
            this.rShoulder = rShoulder;
        }
    }
}
