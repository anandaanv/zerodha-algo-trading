package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.aitrader.v2.rules.ew.dwell.Direction;
import com.dtech.aitrader.v2.rules.ew.dwell.DwellPivot;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pass-1 EW Rule-5 cluster scan, generic across TF per owner directive {@code b954cd6e}.
 * Identifies price levels touched multiple times by reversal pivots and dwell pivots of the
 * same degree. One CLASS, three Spring-managed INSTANCES (Week / Day / OneHour) defined in
 * {@link EwClusterScanConfig}.
 *
 * <p>Each instance:
 * <ul>
 *   <li>Reads {@code ctx.pivotsByTf().get(tf)} for its TF (falls back to {@code ctx.pivots()}
 *       for the legacy single-TF unit-test path).</li>
 *   <li>Reads {@code ctx.dwellPivots()} filtered to its TF (SPEC-010 Phase 1, owner
 *       {@code 59fa728f}).</li>
 *   <li>Emits cluster firings with its own rule-id ({@link EwClusterRuleIds}) so downstream
 *       readers can target a specific TF's clusters.</li>
 * </ul>
 *
 * <p>Algorithm: separate HIGH and LOW pivots and dwell-derived synthetic touches, sort by
 * price, walk through grouping consecutive prices within ±{@code bandPct} of the cluster's
 * running centroid. Clusters with at least {@code minTouches} touches are emitted.
 *
 * <p>Owner override {@code 75b20b10}: clusters are PRICE-LEVEL touches across pivot KINDS,
 * not per-kind. The blessed "~1290-1307 support cluster" mixes LOW 1290 (2026) + HIGH 1307.7
 * (2025) + HIGH 1313 (2022). The cluster's role label is the majority breakdown.
 *
 * <p>Dwell touches per owner {@code f1201a45}: HH-dwell → synthetic LOW touch (support
 * contributor); LL-dwell → synthetic HIGH touch (resistance); INDETERMINATE → LOW default.
 * Identity-tracked via {@link IdentityHashMap} so the {@code dwell_touches} payload count
 * is accurate (NOT a value-based heuristic).
 */
@Slf4j
public class EwClusterScanRule implements Rule {

    private final String tf;
    private final String ruleId;
    private final double bandPct;
    private final int minTouches;

    public EwClusterScanRule(String tf, String ruleId, double bandPct, int minTouches) {
        this.tf = tf;
        this.ruleId = ruleId;
        this.bandPct = bandPct;
        this.minTouches = minTouches;
    }

    @Override public String ruleId() { return ruleId; }
    @Override public Pass pass() { return Pass.P1_STRUCTURAL; }
    @Override public Family family() { return Family.EW; }

    /** The TF this instance scans (Week / Day / OneHour). Exposed for downstream filtering. */
    public String tf() { return tf; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        // Prefer pivots from pivotsByTf if present (real scan-context path); else fall back to
        // the single-TF context.pivots (legacy unit-test path).
        List<MarketStructurePoint> pivots = null;
        if (ctx.getPivotsByTf() != null && ctx.getPivotsByTf().containsKey(tf)) {
            pivots = ctx.getPivotsByTf().get(tf);
        }
        if (pivots == null) pivots = ctx.getPivots();
        if (pivots == null || pivots.isEmpty()) return List.of();

        List<MarketStructurePoint> sorted = new ArrayList<>();
        for (MarketStructurePoint p : pivots) {
            if (p.getPivotType() != null) sorted.add(p);
        }
        Set<MarketStructurePoint> dwellSynthetics =
                Collections.newSetFromMap(new IdentityHashMap<>());
        if (ctx.getDwellPivots() != null) {
            for (DwellPivot d : ctx.getDwellPivots()) {
                if (!tf.equals(d.getTf())) continue;
                MarketStructurePoint touch = asSyntheticTouch(d);
                sorted.add(touch);
                dwellSynthetics.add(touch);
            }
        }
        sorted.sort(Comparator.comparingDouble(MarketStructurePoint::getPrice));
        if (sorted.size() < minTouches) return List.of();

        List<Firing> out = new ArrayList<>();
        List<MarketStructurePoint> current = new ArrayList<>();
        current.add(sorted.get(0));
        for (int i = 1; i < sorted.size(); i++) {
            MarketStructurePoint p = sorted.get(i);
            double centroid = mean(current);
            double deviationPct = Math.abs(p.getPrice() - centroid) / Math.max(centroid, 1e-9) * 100.0;
            if (deviationPct <= bandPct) {
                current.add(p);
            } else {
                emitIfCluster(ctx, current, dwellSynthetics, out);
                current = new ArrayList<>();
                current.add(p);
            }
        }
        emitIfCluster(ctx, current, dwellSynthetics, out);
        return out;
    }

    private void emitIfCluster(SymbolContext ctx, List<MarketStructurePoint> cluster,
                                Set<MarketStructurePoint> dwellSynthetics,
                                List<Firing> sink) {
        if (cluster.size() < minTouches) return;
        double centre = mean(cluster);
        double min = cluster.stream().mapToDouble(MarketStructurePoint::getPrice).min().orElse(centre);
        double max = cluster.stream().mapToDouble(MarketStructurePoint::getPrice).max().orElse(centre);

        long highTouches = cluster.stream().filter(p -> p.getPivotType() == PivotType.HIGH).count();
        long lowTouches  = cluster.stream().filter(p -> p.getPivotType() == PivotType.LOW).count();
        long dwellTouches = cluster.stream().filter(dwellSynthetics::contains).count();
        String role;
        if (highTouches > lowTouches) role = "resistance";
        else if (lowTouches > highTouches) role = "support";
        else role = "mixed";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tf", tf);
        payload.put("centre", centre);
        payload.put("low_edge", min);
        payload.put("high_edge", max);
        payload.put("band_pct", bandPct);
        payload.put("touch_count", cluster.size());
        payload.put("high_touches", (int) highTouches);
        payload.put("low_touches", (int) lowTouches);
        payload.put("dwell_touches", (int) dwellTouches);
        payload.put("role", role);
        payload.put("touch_dates", cluster.stream()
                .map(p -> LocalDate.ofInstant(p.getTimestamp(), ZoneId.of("Asia/Kolkata")).toString())
                .toList());
        payload.put("touch_prices", cluster.stream()
                .map(MarketStructurePoint::getPrice).toList());
        payload.put("touch_kinds", cluster.stream()
                .map(p -> p.getPivotType().name()).toList());

        sink.add(Firing.builder()
                .ruleId(ruleId)
                .symbol(ctx.getSymbol())
                .tf(tf)
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P1_STRUCTURAL)
                .firesOn(FiresOn.FACT)
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build());
    }

    private static double mean(List<MarketStructurePoint> pivots) {
        return pivots.stream().mapToDouble(MarketStructurePoint::getPrice).average().orElse(0.0);
    }

    /**
     * Synthesise a {@link MarketStructurePoint} for cluster aggregation from a {@link DwellPivot}.
     * Per owner {@code f1201a45}: HH-dwell (continuation support) → LOW-kind touch; LL-dwell
     * (continuation resistance) → HIGH-kind touch; INDETERMINATE → LOW (neutral default).
     */
    private static MarketStructurePoint asSyntheticTouch(DwellPivot dwell) {
        PivotType kind = (dwell.getDirection() == Direction.LL) ? PivotType.HIGH : PivotType.LOW;
        return MarketStructurePoint.builder()
                .pivotType(kind)
                .structureLabel(StructureLabel.FIRST)
                .timestamp(dwell.getStartTimestamp())
                .price(dwell.getCenterPrice())
                .atrAtPivot(dwell.getAtrUsed())
                .rsiAtPivot(null)
                .build();
    }
}
