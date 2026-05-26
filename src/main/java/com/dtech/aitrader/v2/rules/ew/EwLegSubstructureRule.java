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
 * Pass-5 Rule 0.95 — leg-substructure confirmation. For each surviving candidate's currently
 * in-progress macro leg (C for zigzag, W2 for impulse), counts the lower-TF sub-pivots and
 * classifies the structure: ≥5 sub-pivots = 5-wave-impulsive; ≤3 = 3-wave-corrective.
 *
 * <p>Direction-of-fit determines whether the sub-structure CONFIRMS or CONTRADICTS the macro
 * framing:
 *
 * <table>
 *   <caption>Confirmation matrix</caption>
 *   <tr><th>Framing</th><th>In-progress leg</th><th>Sub-structure</th><th>Outcome</th></tr>
 *   <tr><td>zigzag</td><td>C</td><td>5-wave impulsive</td><td>CONFIRM (C should be impulsive)</td></tr>
 *   <tr><td>zigzag</td><td>C</td><td>3-wave corrective</td><td>CONTRADICT</td></tr>
 *   <tr><td>impulse</td><td>W2</td><td>3-wave corrective</td><td>CONFIRM (W2 should be corrective)</td></tr>
 *   <tr><td>impulse</td><td>W2</td><td>5-wave impulsive</td><td>CONTRADICT</td></tr>
 * </table>
 *
 * <p>RELIANCE blessed reference {@code cde6bbc9}: "Day rolls over from 1473.4 (2026-05-04 Day
 * CHOCH_HIGH) 1336→1328→1322; Hr last LL (1352→1318) = impulsive-down → CONSISTENT with C
 * starting, INCONSISTENT with corrective W2". Engine target: Hr sub-pivots after 2026-04-29 W1_end
 * = 7 pivots ≈ 5-wave-impulsive → CONFIRM MF1 (C), CONTRADICT MF2 (W2).
 *
 * <p>Reads sub-pivots from {@link SymbolContext#getPivotsByTf()} ("OneHour" preferred; "Day"
 * fallback) — the same multi-TF context that Pass-4 W2-in-progress already uses.
 *
 * <p>Per Q5 convergence (9c60e777): one firing per (candidate, delta) — locked. Each candidate
 * gets at most one CONFIRMATION firing from this rule, based on its own in-progress leg.
 */
@Component
@Slf4j
public class EwLegSubstructureRule implements Rule {

    public static final String RULE_ID = "EW_LEG_SUBSTRUCTURE";

    @Value("${rules.ew.leg-substructure.impulsive-min-pivots:5}")
    private int impulsiveMinPivots = 5;

    @Value("${rules.ew.leg-substructure.corrective-max-pivots:3}")
    private int correctiveMaxPivots = 3;

    @Value("${rules.ew.leg-substructure.confirm-delta:0.05}")
    private double confirmDelta = 0.05;

    @Value("${rules.ew.leg-substructure.contradict-delta:-0.05}")
    private double contradictDelta = -0.05;

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
            Firing confirmation = examine(ctx, cand);
            if (confirmation != null) out.add(confirmation);
        }
        return out;
    }

    private Firing examine(SymbolContext ctx, Firing candidate) {
        String form = (String) candidate.getPayload().get("form");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignment =
                (List<Map<String, Object>>) candidate.getPayload().get("pivot_assignment");
        if (assignment == null) return null;

        String legKind;            // "C" or "W2"
        String legStartDate;
        if ("zigzag".equals(form) && hasRole(assignment, "B_end")) {
            legKind = "C";
            legStartDate = dateOf(assignment, "B_end");
        } else if ("impulse".equals(form) && hasRole(assignment, "W1_end")) {
            legKind = "W2";
            legStartDate = dateOf(assignment, "W1_end");
        } else {
            return null;  // no in-progress leg to examine
        }
        if (legStartDate == null) return null;

        // Prefer OneHour for nano-degree; Day fallback.
        List<MarketStructurePoint> subPivots = subPivotsAfter(ctx, legStartDate, "OneHour");
        String subTf = "OneHour";
        if (subPivots == null || subPivots.isEmpty()) {
            subPivots = subPivotsAfter(ctx, legStartDate, "Day");
            subTf = "Day";
        }
        if (subPivots == null || subPivots.size() < 2) {
            return null;  // indeterminate — too few sub-pivots
        }

        int count = subPivots.size();
        String subStructure;
        if (count >= impulsiveMinPivots) {
            subStructure = "5-wave-impulsive";
        } else if (count <= correctiveMaxPivots) {
            subStructure = "3-wave-corrective";
        } else {
            subStructure = "indeterminate";  // 4 pivots
        }

        // Confirmation matrix.
        boolean confirms;
        double delta;
        if ("C".equals(legKind)) {
            // C should be impulsive
            if ("5-wave-impulsive".equals(subStructure)) { confirms = true; delta = confirmDelta; }
            else if ("3-wave-corrective".equals(subStructure)) { confirms = false; delta = contradictDelta; }
            else return null;
        } else {
            // W2 should be corrective
            if ("3-wave-corrective".equals(subStructure)) { confirms = true; delta = confirmDelta; }
            else if ("5-wave-impulsive".equals(subStructure)) { confirms = false; delta = contradictDelta; }
            else return null;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("leg_kind", legKind);
        payload.put("leg_start_date", legStartDate);
        payload.put("sub_tf", subTf);
        payload.put("sub_pivot_count", count);
        payload.put("sub_structure", subStructure);
        payload.put("confirms_framing", confirms);
        payload.put("candidate_form", form);

        String reason = legKind + " sub-structure on " + subTf + " = " + count
                + " pivots → " + subStructure + " → "
                + (confirms ? "CONFIRMS " : "CONTRADICTS ") + form;

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P5_CONFIRMATION)
                .firesOn(FiresOn.CONFIRMATION)
                .refs(List.of(candidate.getId()))
                .priorDelta(PriorDelta.graduated(delta, reason, "0.95"))
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }

    private List<MarketStructurePoint> subPivotsAfter(SymbolContext ctx, String afterDate, String tf) {
        if (ctx.getPivotsByTf() == null) return null;
        List<MarketStructurePoint> all = ctx.getPivotsByTf().get(tf);
        if (all == null || all.isEmpty()) return null;
        Instant cutoff = LocalDate.parse(afterDate)
                .atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        List<MarketStructurePoint> out = new ArrayList<>();
        for (MarketStructurePoint p : all) {
            if (p.getTimestamp() != null && p.getTimestamp().isAfter(cutoff)) out.add(p);
        }
        return out;
    }

    private static boolean hasRole(List<Map<String, Object>> assignment, String role) {
        return assignment.stream().anyMatch(m -> role.equals(m.get("role")));
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
}
