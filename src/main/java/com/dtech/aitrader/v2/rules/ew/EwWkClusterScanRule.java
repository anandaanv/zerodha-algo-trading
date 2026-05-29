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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * Pass-1 EW Rule-5 cluster scan. Identifies price levels touched multiple times by pivots of the
 * same kind (resistance for HIGHs, support for LOWs). Emits one FACT per identified cluster.
 *
 * <p>Algorithm: separate HIGH and LOW pivots, sort by price, walk through grouping consecutive
 * prices within ±{@link #bandPct}% of the cluster's running centroid. Clusters with at least
 * {@link #minTouches} pivots are emitted.
 *
 * <p>Defaults (configurable via {@code rules.ew.cluster.*}):
 * <ul>
 *   <li>{@code band_pct} = 2.0% (per spec ab9bd541)</li>
 *   <li>{@code min_touches} = 3 (per blessed reference cde6bbc9 criterion b)</li>
 * </ul>
 *
 * <p>Acceptance — RELIANCE blessed reference (criterion b): the 3 clusters ~1361, ~1290-1307,
 * ~1473-1489 must all surface with touch_count ≥ 3.
 */
@Component
@Slf4j
public class EwWkClusterScanRule implements Rule {

    public static final String RULE_ID = "EW_WK_CLUSTER_SCAN";

    // Field initializers ensure the production defaults apply even when the rule is constructed
    // directly via `new` in unit tests (where Spring's @Value injection doesn't run).
    @Value("${rules.ew.cluster.band-pct:2.0}")
    private double bandPct = 2.0;

    @Value("${rules.ew.cluster.min-touches:3}")
    private int minTouches = 3;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P1_STRUCTURAL; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        // Prefer Wk pivots from pivotsByTf if present (real scan-context path); else fall back to
        // the single-TF context.pivots (unit-test path).
        List<MarketStructurePoint> pivots = null;
        if (ctx.getPivotsByTf() != null && ctx.getPivotsByTf().containsKey("Week")) {
            pivots = ctx.getPivotsByTf().get("Week");
        }
        if (pivots == null) pivots = ctx.getPivots();
        if (pivots == null || pivots.isEmpty()) return List.of();

        // Owner override (75b20b10): clusters are PRICE-LEVEL touches across pivot KINDS, not
        // per-kind. The blessed "~1290-1307 support cluster" mixes LOW 1290 (2026) + HIGH 1307.7
        // (2025) + HIGH 1313 (2022). The cluster's TYPE is labelled from the majority breakdown.
        //
        // SPEC-010 Phase 1 ({@code 60d21c43}): dwell pivots from {@link SymbolContext#getDwellPivots()}
        // also contribute touches per owner direction {@code 59fa728f}. HH-dwells (continuation
        // support per {@code f1201a45}) are mapped to synthetic LOW-kind touches; LL-dwells
        // (continuation resistance) to synthetic HIGH-kind touches. INDETERMINATE dwells are
        // included as MIXED — they participate in cluster aggregation but carry no role bias.
        List<MarketStructurePoint> sorted = new ArrayList<>();
        for (MarketStructurePoint p : pivots) {
            if (p.getPivotType() != null) sorted.add(p);
        }
        // Track synthetic dwell-derived touches by reference identity (NOT a value heuristic)
        // so downstream payloads can report dwell_touches accurately without conflating with
        // real reversal pivots that happen to have null RSI etc.
        Set<MarketStructurePoint> dwellSynthetics =
                Collections.newSetFromMap(new IdentityHashMap<>());
        if (ctx.getDwellPivots() != null) {
            for (DwellPivot d : ctx.getDwellPivots()) {
                if (!"Week".equals(d.getTf())) continue;
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
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
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
     * Synthesise a {@link MarketStructurePoint} that the cluster algorithm can consume from a
     * {@link DwellPivot}. Per owner {@code f1201a45}: HH-dwell (continuation support) maps to
     * LOW-kind touch; LL-dwell (continuation resistance) maps to HIGH-kind touch; INDETERMINATE
     * to LOW (treated as neutral / will not bias role aggregation away from true support).
     * Marked via {@link StructureLabel#FIRST} so {@link #isDwellSynthetic(MarketStructurePoint)}
     * can identify it for the {@code dwell_touches} payload field.
     */
    private static MarketStructurePoint asSyntheticTouch(DwellPivot dwell) {
        PivotType kind = (dwell.getDirection() == Direction.LL) ? PivotType.HIGH : PivotType.LOW;
        return MarketStructurePoint.builder()
                .pivotType(kind)
                .structureLabel(StructureLabel.FIRST) // marker — cluster scan only reads pivotType
                .timestamp(dwell.getStartTimestamp())
                .price(dwell.getCenterPrice())
                .atrAtPivot(dwell.getAtrUsed())
                .rsiAtPivot(null)
                .build();
    }

}
