package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        List<MarketStructurePoint> sorted = pivots.stream()
                .filter(p -> p.getPivotType() != null)
                .sorted(Comparator.comparingDouble(MarketStructurePoint::getPrice))
                .toList();
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
                emitIfCluster(ctx, current, out);
                current = new ArrayList<>();
                current.add(p);
            }
        }
        emitIfCluster(ctx, current, out);
        return out;
    }

    private void emitIfCluster(SymbolContext ctx, List<MarketStructurePoint> cluster,
                                List<Firing> sink) {
        if (cluster.size() < minTouches) return;
        double centre = mean(cluster);
        double min = cluster.stream().mapToDouble(MarketStructurePoint::getPrice).min().orElse(centre);
        double max = cluster.stream().mapToDouble(MarketStructurePoint::getPrice).max().orElse(centre);

        long highTouches = cluster.stream().filter(p -> p.getPivotType() == PivotType.HIGH).count();
        long lowTouches  = cluster.stream().filter(p -> p.getPivotType() == PivotType.LOW).count();
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
}
