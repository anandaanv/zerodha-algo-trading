package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-4 confluence boost: if a candidate's structural level (counter-extreme, B_end / W1_end, or
 * projected target) aligns with a Pass-1 EwWkClusterScan cluster centre (within
 * {@code rules.ew.confluence.band-pct}, default 2%), emit a GRADUATED CLASSIFICATION firing
 * boosting the candidate's prior.
 *
 * <p>Per blessed RELIANCE cde6bbc9, MF1 zigzag's counter (1290 LL) sits on the ~1290-1307 support
 * cluster, and its B_end (1473.4) sits on the ~1473-1489 resistance cluster. Both produce
 * confluence boosts.
 *
 * <p>Per Q5 / convergence (9c60e777): one firing per (candidate, delta) — so multiple alignments
 * emit multiple firings, each GRADUATED with a small boost. Pass-6 sums them via the chain fold.
 */
@Component
@Slf4j
public class EwClusterConfluenceRule implements Rule {

    public static final String RULE_ID = "EW_CLUSTER_CONFLUENCE";

    @Value("${rules.ew.confluence.band-pct:2.0}")
    private double bandPct = 2.0;

    @Value("${rules.ew.confluence.delta-per-alignment:0.03}")
    private double deltaPerAlignment = 0.03;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P4_CLASSIFICATION; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        List<Firing> clusters = priorFirings.stream()
                .filter(f -> f.getFamily() == Family.EW)
                .filter(f -> f.getFiresOn() == FiresOn.FACT)
                .filter(f -> EwClusterRuleIds.WK.equals(f.getRuleId()))
                .toList();
        List<Firing> candidates = priorFirings.stream()
                .filter(f -> f.getFamily() == Family.EW)
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .toList();
        if (clusters.isEmpty() || candidates.isEmpty()) return List.of();

        java.util.Set<String> eliminated = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.ELIMINATION && f.getRefs() != null)
                .flatMap(f -> f.getRefs().stream())
                .collect(java.util.stream.Collectors.toSet());

        List<Firing> out = new ArrayList<>();
        for (Firing cand : candidates) {
            if (eliminated.contains(cand.getId())) continue;

            Map<String, Double> keyLevels = keyLevelsOf(cand);
            for (Firing cluster : clusters) {
                double centre = ((Number) cluster.getPayload().get("centre")).doubleValue();
                for (Map.Entry<String, Double> kv : keyLevels.entrySet()) {
                    double level = kv.getValue();
                    double deviationPct = Math.abs(level - centre) / Math.max(centre, 1e-9) * 100.0;
                    if (deviationPct <= bandPct) {
                        out.add(emitAlignment(ctx, cand, cluster, kv.getKey(), level, centre,
                                deviationPct));
                    }
                }
            }
        }
        return out;
    }

    /** Derive structural levels per form. */
    private Map<String, Double> keyLevelsOf(Firing candidate) {
        Map<String, Double> out = new LinkedHashMap<>();
        String form = (String) candidate.getPayload().get("form");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignment =
                (List<Map<String, Object>>) candidate.getPayload().get("pivot_assignment");
        if (assignment == null) return out;

        Double aStart = priceOf(assignment, "A_start");
        Double aEnd = priceOf(assignment, "A_end");
        Double bEnd = priceOf(assignment, "B_end");
        Double w0 = priceOf(assignment, "W0");
        Double w1End = priceOf(assignment, "W1_end");

        if ("zigzag".equals(form) && aStart != null && aEnd != null) {
            out.put("A_start_invalidation", aStart);   // above A_start kills the corrective
            out.put("A_end_counter", aEnd);             // the counter-extreme low
            if (bEnd != null) {
                out.put("B_end", bEnd);
                // C target = B_end − A magnitude (downside zigzag) or + (upside)
                double aMag = Math.abs(aEnd - aStart);
                boolean down = aStart > aEnd;
                double cTarget = down ? bEnd - aMag : bEnd + aMag;
                out.put("C_target", cTarget);
            }
        } else if ("impulse".equals(form) && w0 != null && w1End != null) {
            out.put("W0_invalidation", w0);             // below W0 kills the impulse (bullish)
            out.put("W1_end", w1End);
            // W3 target ≈ W1_end + 1.618 × W1_magnitude (very rough — refine in Pass-5)
            double w1Mag = Math.abs(w1End - w0);
            boolean bullish = w1End > w0;
            double w3Target = bullish ? w1End + 1.618 * w1Mag : w1End - 1.618 * w1Mag;
            out.put("W3_target_approx", w3Target);
        }
        return out;
    }

    private Firing emitAlignment(SymbolContext ctx, Firing candidate, Firing cluster,
                                   String levelName, double level, double centre, double deviationPct) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aligned_role", levelName);
        payload.put("candidate_level", level);
        payload.put("cluster_centre", centre);
        payload.put("cluster_ref", cluster.getId());
        payload.put("deviation_pct", deviationPct);
        payload.put("cluster_role", cluster.getPayload().get("role"));

        String reason = levelName + " (" + round(level) + ") aligns with cluster centre "
                + round(centre) + " (deviation " + round(deviationPct) + "%)";

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P4_CLASSIFICATION)
                .firesOn(FiresOn.CLASSIFICATION)
                .refs(List.of(candidate.getId()))
                .priorDelta(PriorDelta.graduated(deltaPerAlignment, reason, "confluence"))
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }

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

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
