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
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-5 rule — pre-conclusion price-confirmation gate per SPEC reframe ({@code 159ba913}).
 *
 * <p>For each EW candidate, emits a CONFIRMATION firing carrying {@code gate_state}:
 * <ul>
 *   <li>{@code CONFIRMED} — price has broken the candidate's reference level (e.g. for a
 *       corrective zigzag SHORT thesis, the prior counter LOW has been broken; for an impulse
 *       LONG thesis, the prior swing HIGH has been broken).</li>
 *   <li>{@code UNCONFIRMED} — the reference level has NOT been broken yet. The directional
 *       thesis is hypothesis-only; no high-confidence VERDICT may fire.</li>
 * </ul>
 *
 * <p>Pass-6 reads this gate state alongside the signature-admission results and the SPEC-006
 * wave-completion state. VERDICT fires only when ALL gates pass; otherwise the engine emits
 * the level-map + WATCH.
 *
 * <p>"Current price" is approximated by the latest-timestamped pivot in the SymbolContext's
 * cross-TF pivot map (preferring Hr, falling back to Day / Wk). When OHLCV bars are eventually
 * loaded into the context, this rule can read the latest close directly — until then the
 * latest-pivot proxy is sufficient for the binary CONFIRMED/UNCONFIRMED decision.
 */
@Component
@Slf4j
public class EwPreConclusionGateRule implements Rule {

    public static final String RULE_ID = "EW_PRE_CONCLUSION_GATE";

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

        Double currentPrice = mostRecentPivotPrice(ctx);
        if (currentPrice == null) {
            log.debug("[ew-gate] no pivots in context — cannot compute current price");
            return List.of();
        }
        String currentPriceSource = mostRecentPivotSource(ctx);

        java.util.List<Firing> out = new java.util.ArrayList<>();
        for (Firing cand : candidates) {
            if (eliminated.contains(cand.getId())) continue;
            Firing fired = examine(ctx, cand, currentPrice, currentPriceSource);
            if (fired != null) out.add(fired);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Firing examine(SymbolContext ctx, Firing candidate, double currentPrice,
                             String currentPriceSource) {
        Map<String, Object> p = candidate.getPayload();
        String form = (String) p.get("form");
        List<Map<String, Object>> assignment = (List<Map<String, Object>>) p.get("pivot_assignment");
        if (form == null || assignment == null) return null;

        // Resolve reference level + direction the gate is checking for.
        String referenceRole;
        Double referenceLevel;
        boolean breakIsDown;
        String thesisDirection;
        if ("zigzag".equals(form)) {
            referenceRole = "A_end";
            referenceLevel = priceOf(assignment, referenceRole);
            Double aStart = priceOf(assignment, "A_start");
            if (aStart == null || referenceLevel == null) return null;
            breakIsDown = aStart > referenceLevel;
            thesisDirection = breakIsDown ? "SHORT" : "LONG";
        } else if ("impulse".equals(form)) {
            referenceRole = "W1_end";
            referenceLevel = priceOf(assignment, referenceRole);
            Double w0 = priceOf(assignment, "W0");
            if (w0 == null || referenceLevel == null) return null;
            breakIsDown = w0 > referenceLevel;
            thesisDirection = breakIsDown ? "SHORT" : "LONG";
        } else {
            return null;
        }

        boolean confirmed = breakIsDown
                ? (currentPrice < referenceLevel)
                : (currentPrice > referenceLevel);
        String gateState = confirmed ? "CONFIRMED" : "UNCONFIRMED";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gate_state", gateState);
        payload.put("thesis_direction", thesisDirection);
        payload.put("reference_role", referenceRole);
        payload.put("reference_level", referenceLevel);
        payload.put("current_price", currentPrice);
        payload.put("current_price_source", currentPriceSource);
        payload.put("break_direction", breakIsDown ? "below" : "above");
        payload.put("awaiting_break_of", confirmed ? null : referenceLevel);
        payload.put("candidate_form", form);
        payload.put("reasoning", String.format(
                "current price %.2f (%s) %s reference %.2f (%s) → thesis %s %s",
                currentPrice, currentPriceSource,
                confirmed ? "has broken" : "has NOT broken",
                referenceLevel, referenceRole,
                thesisDirection,
                confirmed ? "CONFIRMED" : "UNCONFIRMED — engine cannot emit a high-confidence VERDICT; surface the level-map instead"));

        PriorDelta delta = PriorDelta.graduated(0.0, payload.get("reasoning").toString(), RULE_ID);

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P5_CONFIRMATION)
                .firesOn(FiresOn.CONFIRMATION)
                .refs(List.of(candidate.getId()))
                .priorDelta(delta)
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }

    private static Double mostRecentPivotPrice(SymbolContext ctx) {
        MarketStructurePoint best = mostRecentPivot(ctx);
        return best == null ? null : best.getPrice();
    }

    private static String mostRecentPivotSource(SymbolContext ctx) {
        if (ctx.getPivotsByTf() == null) return null;
        Instant bestTs = null;
        String bestTf = null;
        for (Map.Entry<String, List<MarketStructurePoint>> entry : ctx.getPivotsByTf().entrySet()) {
            for (MarketStructurePoint p : entry.getValue()) {
                if (p.getTimestamp() == null) continue;
                if (bestTs == null || p.getTimestamp().isAfter(bestTs)) {
                    bestTs = p.getTimestamp();
                    bestTf = entry.getKey();
                }
            }
        }
        return bestTf == null ? null : bestTf + " latest pivot @ " + bestTs;
    }

    private static MarketStructurePoint mostRecentPivot(SymbolContext ctx) {
        if (ctx.getPivotsByTf() == null) return null;
        MarketStructurePoint best = null;
        for (List<MarketStructurePoint> pivots : ctx.getPivotsByTf().values()) {
            for (MarketStructurePoint p : pivots) {
                if (p.getTimestamp() == null) continue;
                if (best == null || p.getTimestamp().isAfter(best.getTimestamp())) best = p;
            }
        }
        return best;
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
}
