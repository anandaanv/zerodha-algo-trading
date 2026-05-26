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
 * Pass-2 candidate emitter for the bullish INVERSE Head &amp; Shoulders pattern per SPEC-008
 * ({@code e332be7f}) with completion measured CONTINUOUSLY per owner correction
 * ({@code 89a52589}). Mirror of {@link HnsDetectRule} on LOW pivots with intervening HIGH peaks.
 *
 * <p>Backbone gate: L-shoulder + peak1 + head (head ≤ L-shoulder − 1×ATR) + ordering. Completion
 * formula matches HnS — see that rule's class doc for the geometric breakdown.
 */
@Component
@Slf4j
public class InverseHnsDetectRule implements Rule {

    public static final String RULE_ID = "INVERSE_HNS_DETECT";

    private static final int ATR_PERIOD = 14;
    private static final double SHOULDER_EQUAL_TOL_ATR = 1.0;
    private static final double HEAD_DEPTH_MIN_ATR = 1.0;
    private static final double PEAK_EQUAL_TOL_ATR = 1.5;
    private static final int MIN_LEG_BARS = 3;
    private static final int MAX_LEG_BARS = 50;
    private static final int MAX_PATTERN_SPAN_BARS = 250;
    private static final double BASE_PRIOR = 0.40;
    /** Shoulder-depth gate per owner {@code fbabb0b9} — mirror of HnsDetectRule's gate. */
    private static final double MIN_SHOULDER_DEPTH_RATIO = 0.23;
    private static final double TEXTBOOK_SHOULDER_DEPTH_RATIO = 0.50;
    /** Span-scaled shoulder asymmetry (owner {@code 474986f0}) — mirror of HnsDetectRule. */
    private static final double BASE_SHOULDER_ASYM_PCT = 0.03;
    private static final double SPAN_SHOULDER_ASYM_PCT = 0.04;
    private static final int SPAN_SCALE_MIN_BARS = 10;
    private static final int SPAN_SCALE_MAX_BARS = 110;

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
        List<PivotRef> allLows = sortedPivots(pivots, indexer, PivotType.LOW);
        List<PivotRef> allHighs = sortedPivots(pivots, indexer, PivotType.HIGH);
        if (allLows.isEmpty()) return List.of();

        double closeNow = series.getBar(endIdx).getClosePrice().doubleValue();
        double closePrev = series.getBar(endIdx - 1).getClosePrice().doubleValue();

        // Owner 474986f0: scan ALL L-H-L-H-L triples in the lookback (mirror of HnS scan).
        List<Firing> out = new ArrayList<>();
        java.util.Set<Integer> emittedHeads = new java.util.HashSet<>();

        for (int li = 0; li < allLows.size() - 2; li++) {
            PivotRef L = allLows.get(li);
            for (int hi = li + 1; hi < allLows.size() - 1; hi++) {
                PivotRef H = allLows.get(hi);
                if (H.idx - L.idx < MIN_LEG_BARS) continue;
                for (int ri = hi + 1; ri < allLows.size(); ri++) {
                    PivotRef R = allLows.get(ri);
                    if (R.idx - H.idx < MIN_LEG_BARS) continue;
                    if (R.idx - L.idx > MAX_PATTERN_SPAN_BARS) continue;
                    PivotRef p1 = findHighBetween(allHighs, L.idx, H.idx);
                    PivotRef p2 = findHighBetween(allHighs, H.idx, R.idx);
                    if (p1 == null || p2 == null) continue;
                    Optional5Pivot window = new Optional5Pivot(L, p1, H, p2, R);
                    if (!passesBackboneGeometry(window, atr)) continue;

                    double neckline = necklineAt(window, endIdx);
                    boolean broke = closePrev <= neckline && closeNow > neckline;
                    boolean alreadyAboveNeckline = closeNow > neckline;
                    boolean confirmedBreak = broke || alreadyAboveNeckline;
                    double completion = computeCompletion(window, atr, closeNow, neckline, confirmedBreak);
                    if (completion < EMISSION_THRESHOLD) continue;

                    if (!emittedHeads.add(H.idx)) continue;

                    String status = completion >= CONFIRMED_THRESHOLD ? "confirmed" : "forming";
                    List<String> earlySigns = collectEarlySigns(window, closeNow, neckline, confirmedBreak);
                    out.add(buildFiring(ctx, window, neckline, atr, endIdx, closeNow,
                            status, completion, earlySigns));
                }
            }
        }

        // Partial 2-window: head=latest LOW.
        if (allLows.size() >= 2) {
            PivotRef L = allLows.get(allLows.size() - 2);
            PivotRef H = allLows.get(allLows.size() - 1);
            if (H.idx - L.idx >= MIN_LEG_BARS && !emittedHeads.contains(H.idx)) {
                PivotRef p1 = findHighBetween(allHighs, L.idx, H.idx);
                if (p1 != null) {
                    Optional5Pivot partial = new Optional5Pivot(L, p1, H, null, null);
                    if (passesBackbonePartial(partial, atr)) {
                        double neckline = p1.price;
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

    private static PivotRef findHighBetween(List<PivotRef> highs, int leftIdx, int rightIdx) {
        PivotRef best = null;
        for (PivotRef p : highs) {
            if (p.idx <= leftIdx || p.idx >= rightIdx) continue;
            if (best == null || p.price > best.price) best = p;
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

    private double computeCompletion(Optional5Pivot w, double atr, double closeNow,
                                       double neckline, boolean confirmedBreak) {
        if (w.lShoulder == null || w.peak1 == null || w.head == null) return 0.0;
        if (w.head.price > w.lShoulder.price - HEAD_DEPTH_MIN_ATR * atr) return 0.0;
        if (!(w.lShoulder.idx < w.peak1.idx && w.peak1.idx < w.head.idx)) return 0.0;

        double c = 20.0;

        double rolloverRange = Math.max(1e-9, w.peak1.price - w.head.price);
        double rolloverFrac = clamp01((closeNow - w.head.price) / rolloverRange);
        c += 25.0 * rolloverFrac;

        if (w.peak2 != null) {
            c += 10.0;
            double peakErr = Math.abs(w.peak1.price - w.peak2.price)
                    / Math.max(1e-9, atr * PEAK_EQUAL_TOL_ATR);
            c += 5.0 * clamp01(1.0 - peakErr);
        }

        if (w.rShoulder != null) {
            c += 10.0;
            double shoulderErr = Math.abs(w.lShoulder.price - w.rShoulder.price)
                    / Math.max(1e-9, atr * SHOULDER_EQUAL_TOL_ATR);
            c += 5.0 * clamp01(1.0 - shoulderErr);

            if (neckline > 0 && w.rShoulder.price < neckline && closeNow < neckline) {
                double approachRange = Math.max(1e-9, neckline - w.rShoulder.price);
                double approachFrac = clamp01(1.0 - (neckline - closeNow) / approachRange);
                c += 10.0 * approachFrac;
            }
        }

        // Owner fbabb0b9: depth quality score [0, 5]. Mirror of HnsDetectRule.
        double depthRatio = (w.peak2 != null) ? shoulderDepthRatioFull(w) : shoulderDepthRatioPartial(w);
        double depthRange = TEXTBOOK_SHOULDER_DEPTH_RATIO - MIN_SHOULDER_DEPTH_RATIO;
        double depthQuality = clamp01((depthRatio - MIN_SHOULDER_DEPTH_RATIO) / depthRange);
        c += 5.0 * depthQuality;

        if (confirmedBreak) {
            c = Math.max(c, 85.0) + 15.0;
        }
        return Math.min(100.0, c);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private boolean passesBackboneGeometry(Optional5Pivot w, double atr) {
        if (!shouldersSufficientlyEqual(w)) return false;
        if (w.head.price > Math.min(w.lShoulder.price, w.rShoulder.price) - HEAD_DEPTH_MIN_ATR * atr) return false;
        if (Math.abs(w.peak1.price - w.peak2.price) > PEAK_EQUAL_TOL_ATR * atr) return false;
        // Sloped-neckline check (mirror of HnS): shoulders must sit BELOW the extrapolated
        // neckline at their bar idx, head must sit BELOW (deeper than) the extrapolated
        // neckline. Replaces strict "both peaks above both shoulders" gate.
        double necklineAtL = necklineAt(w, w.lShoulder.idx);
        double necklineAtR = necklineAt(w, w.rShoulder.idx);
        double necklineAtHead = necklineAt(w, w.head.idx);
        if (w.lShoulder.price >= necklineAtL) return false;
        if (w.rShoulder.price >= necklineAtR) return false;
        if (w.head.price >= necklineAtHead) return false;
        if (!(w.lShoulder.idx < w.peak1.idx && w.peak1.idx < w.head.idx
                && w.head.idx < w.peak2.idx && w.peak2.idx < w.rShoulder.idx)) return false;
        if (!legSpansValid(w.lShoulder, w.peak1, w.head, w.peak2, w.rShoulder)) return false;
        if (shoulderDepthRatioFull(w) < MIN_SHOULDER_DEPTH_RATIO) return false;
        return true;
    }

    private boolean shouldersSufficientlyEqual(Optional5Pivot w) {
        double asymPct = Math.abs(w.lShoulder.price - w.rShoulder.price) / w.head.price;
        int legBars = w.rShoulder.idx - w.lShoulder.idx;
        double spanFactor = clamp01((legBars - SPAN_SCALE_MIN_BARS)
                / (double) (SPAN_SCALE_MAX_BARS - SPAN_SCALE_MIN_BARS));
        double tolerancePct = BASE_SHOULDER_ASYM_PCT + SPAN_SHOULDER_ASYM_PCT * spanFactor;
        return asymPct <= tolerancePct;
    }

    private boolean passesBackbonePartial(Optional5Pivot w, double atr) {
        if (w.head.price > w.lShoulder.price - HEAD_DEPTH_MIN_ATR * atr) return false;
        if (!(w.lShoulder.idx < w.peak1.idx && w.peak1.idx < w.head.idx)) return false;
        if (!legSpansValid(w.lShoulder, w.peak1, w.head)) return false;
        if (shoulderDepthRatioPartial(w) < MIN_SHOULDER_DEPTH_RATIO) return false;
        return true;
    }

    /**
     * Shoulder-depth ratio for inverse H&amp;S: shoulder BELOW neckline divided by head BELOW
     * neckline. Mirror of {@link HnsDetectRule#shoulderDepthRatioFull}. Conservative neckline
     * proxy = min(peak1, peak2) — closer to the shoulders, so flat patterns can't game it.
     */
    private static double shoulderDepthRatioFull(Optional5Pivot w) {
        double maxShoulder = Math.max(w.lShoulder.price, w.rShoulder.price);
        double necklineProxy = Math.min(w.peak1.price, w.peak2.price);
        double headDepth = necklineProxy - w.head.price;
        double shoulderDepth = necklineProxy - maxShoulder;
        if (headDepth <= 0) return 0.0;
        return shoulderDepth / headDepth;
    }

    private static double shoulderDepthRatioPartial(Optional5Pivot w) {
        double headDepth = w.peak1.price - w.head.price;
        double shoulderDepth = w.peak1.price - w.lShoulder.price;
        if (headDepth <= 0) return 0.0;
        return shoulderDepth / headDepth;
    }

    private boolean legSpansValid(PivotRef... pivots) {
        for (int i = 1; i < pivots.length; i++) {
            int span = pivots[i].idx - pivots[i - 1].idx;
            if (span < MIN_LEG_BARS || span > MAX_LEG_BARS) return false;
        }
        return true;
    }

    private static double necklineAt(Optional5Pivot w, int atIdx) {
        double dx = w.peak2.idx - w.peak1.idx;
        if (dx == 0) return w.peak1.price;
        double slope = (w.peak2.price - w.peak1.price) / dx;
        return w.peak1.price + slope * (atIdx - w.peak1.idx);
    }

    private static Optional5Pivot tryFiveWindow(List<PivotRef> lows, List<PivotRef> highs) {
        if (lows.size() < 3 || highs.size() < 2) return null;
        PivotRef l = lows.get(lows.size() - 3);
        PivotRef h = lows.get(lows.size() - 2);
        PivotRef r = lows.get(lows.size() - 1);
        PivotRef p1 = highs.get(highs.size() - 2);
        PivotRef p2 = highs.get(highs.size() - 1);
        return new Optional5Pivot(l, p1, h, p2, r);
    }

    private static Optional5Pivot buildPartialWindow(List<PivotRef> lows, List<PivotRef> highs) {
        if (lows.size() < 2 || highs.isEmpty()) return null;
        PivotRef l = lows.get(0);
        PivotRef h = lows.get(1);
        PivotRef p1 = highs.get(highs.size() - 1);
        if (!(p1.idx > l.idx && p1.idx < h.idx)) return null;
        return new Optional5Pivot(l, p1, h, null, null);
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

    private List<String> collectEarlySigns(Optional5Pivot w, double closeNow, double neckline,
                                             boolean confirmedBreak) {
        List<String> signs = new ArrayList<>();
        signs.add("head_lower_than_l_shoulder_by_at_least_1_atr");
        if (closeNow > w.head.price) signs.add("post_head_rollup_started");
        if (w.peak2 == null) signs.add("first_higher_low_pending");
        if (w.rShoulder != null) signs.add("right_shoulder_built");
        if (w.rShoulder != null && !confirmedBreak) signs.add("neckline_unbroken");
        if (confirmedBreak) signs.add("neckline_broken");
        return signs;
    }

    private Firing buildFiring(SymbolContext ctx, Optional5Pivot w, double neckline, double atr,
                                 int endIdx, double closeNow, String status, double completion,
                                 List<String> earlySigns) {
        double necklineAtHead = (w.peak2 != null)
                ? necklineAt(w, w.head.idx)
                : w.peak1.price;
        double headDepth = necklineAtHead - w.head.price;
        double targetPrice = neckline + headDepth;
        double invalidationPrice = w.head.price - 0.5 * atr;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("completion_pct", completion);
        payload.put("early_signs", earlySigns);
        payload.put("bias", "LONG");
        payload.put("l_shoulder_idx", w.lShoulder.idx);
        payload.put("l_shoulder_price", w.lShoulder.price);
        payload.put("head_idx", w.head.idx);
        payload.put("head_price", w.head.price);
        if (w.rShoulder != null) {
            payload.put("r_shoulder_idx", w.rShoulder.idx);
            payload.put("r_shoulder_price", w.rShoulder.price);
        }
        payload.put("peak1_idx", w.peak1.idx);
        payload.put("peak1_price", w.peak1.price);
        if (w.peak2 != null) {
            payload.put("peak2_idx", w.peak2.idx);
            payload.put("peak2_price", w.peak2.price);
        }
        payload.put("neckline_price", neckline);
        payload.put("head_depth", headDepth);
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
        final PivotRef peak1;
        final PivotRef head;
        final PivotRef peak2;
        final PivotRef rShoulder;

        Optional5Pivot(PivotRef lShoulder, PivotRef peak1, PivotRef head,
                         PivotRef peak2, PivotRef rShoulder) {
            this.lShoulder = lShoulder;
            this.peak1 = peak1;
            this.head = head;
            this.peak2 = peak2;
            this.rShoulder = rShoulder;
        }
    }
}
