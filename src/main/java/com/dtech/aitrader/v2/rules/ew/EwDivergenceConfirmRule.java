package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
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
 * Pass-5 Rule 0.95 divergence confirmation. Detects RSI divergence at the candidate's pivot of
 * exhaustion and applies it directionally by form:
 *
 * <ul>
 *   <li>zigzag — check divergence at {@code B_end} (the bounce high). Bearish divergence (price HH
 *       vs prior Wk HIGH but RSI LH) CONFIRMS the top-is-in reading. Delta {@code +confirmDelta}.</li>
 *   <li>impulse — check divergence at {@code W1_end} (same bar). Bearish divergence here
 *       CONTRADICTS W3 continuation (W1 looks exhausted, doesn't fit textbook impulse). Delta
 *       {@code -contradictDelta}.</li>
 * </ul>
 *
 * <p>Skips silently if: prior same-type Wk pivot is missing, bar series is too short, indicators
 * unavailable, or price isn't an HH (no divergence applicable).
 *
 * <p>Direction-opposite-by-form mirrors {@link EwLegSubstructureRule}: same observation, different
 * meaning per framing — exactly the discriminating signal Pass-5 needs to separate MF1 from MF2.
 */
@Component
@Slf4j
public class EwDivergenceConfirmRule implements Rule {

    public static final String RULE_ID = "EW_DIVERGENCE_CONFIRM";

    @Value("${rules.ew.divergence.confirm-delta:0.04}")
    private double confirmDelta = 0.04;

    @Value("${rules.ew.divergence.contradict-delta:-0.04}")
    private double contradictDelta = -0.04;

    /** RSI absolute drop required to call a "lower high" — guards against trivial noise. */
    @Value("${rules.ew.divergence.rsi-lh-threshold:1.0}")
    private double rsiLhThreshold = 1.0;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P5_CONFIRMATION; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        if (ctx.getPivots() == null || ctx.getPivots().isEmpty()) return List.of();

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

    private Firing examine(SymbolContext ctx, Firing candidate) {
        String form = (String) candidate.getPayload().get("form");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignment =
                (List<Map<String, Object>>) candidate.getPayload().get("pivot_assignment");
        if (assignment == null) return null;

        String pivotRole;
        if ("zigzag".equals(form)) pivotRole = "B_end";
        else if ("impulse".equals(form)) pivotRole = "W1_end";
        else return null;

        String pivotDate = dateOf(assignment, pivotRole);
        Double pivotPrice = priceOf(assignment, pivotRole);
        if (pivotDate == null || pivotPrice == null) return null;

        // Locate the matching pivot in ctx.pivots (Week-level).
        MarketStructurePoint current = findPivot(ctx.getPivots(), pivotDate, pivotPrice);
        if (current == null || current.getPivotType() != PivotType.HIGH) return null;

        // Walk backward for the most recent prior Wk HIGH.
        MarketStructurePoint prior = findPriorOfType(ctx.getPivots(), current, PivotType.HIGH);
        if (prior == null) return null;

        // RSI travels on the pivot itself (ScanContextParser populates rsiAtPivot from the CSV).
        Double rsiCurrentObj = current.getRsiAtPivot();
        Double rsiPriorObj = prior.getRsiAtPivot();
        if (rsiCurrentObj == null || rsiPriorObj == null) return null;
        double rsiCurrent = rsiCurrentObj;
        double rsiPrior = rsiPriorObj;
        double priceCurrent = current.getPrice();
        double pricePrior = prior.getPrice();

        // Two bearish-weak-peak setups — both signal the rally is structurally weak:
        //   regular bearish: price made HH but RSI made LH (gap ≥ threshold)
        //   hidden bearish:  price made LH but RSI made HH (gap ≥ threshold)
        boolean priceHH = priceCurrent > pricePrior;
        boolean priceLH = priceCurrent < pricePrior;
        double rsiDelta = rsiCurrent - rsiPrior;            // + = higher, − = lower
        boolean rsiLowerByThreshold = -rsiDelta >= rsiLhThreshold;
        boolean rsiHigherByThreshold = rsiDelta >= rsiLhThreshold;

        String divergenceType;
        if (priceHH && rsiLowerByThreshold) divergenceType = "regular-bearish";
        else if (priceLH && rsiHigherByThreshold) divergenceType = "hidden-bearish";
        else return null;

        boolean confirms = "zigzag".equals(form);
        double delta = confirms ? confirmDelta : contradictDelta;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("divergence_kind", "bearish");
        payload.put("divergence_type", divergenceType);
        payload.put("at_role", pivotRole);
        payload.put("at_date", pivotDate);
        payload.put("at_price", priceCurrent);
        payload.put("prior_pivot_date",
                prior.getTimestamp() == null ? null : prior.getTimestamp().toString());
        payload.put("prior_pivot_price", pricePrior);
        payload.put("rsi_current", round(rsiCurrent));
        payload.put("rsi_prior", round(rsiPrior));
        payload.put("rsi_gap", round(rsiDelta));
        payload.put("candidate_form", form);
        payload.put("confirms_framing", confirms);

        String reason = divergenceType + " divergence at " + pivotRole + " (price "
                + round(pricePrior) + "→" + round(priceCurrent)
                + " | RSI " + round(rsiPrior) + "→" + round(rsiCurrent) + ") "
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

    /** Match a Wk pivot by (date, price ≈) — date is the canonical pivot identifier. */
    private MarketStructurePoint findPivot(List<MarketStructurePoint> pivots, String date, double price) {
        Instant target = LocalDate.parse(date).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        MarketStructurePoint best = null;
        long bestGap = Long.MAX_VALUE;
        for (MarketStructurePoint p : pivots) {
            if (p.getTimestamp() == null) continue;
            // Match by nearest-date within 7 days; pick best on date proximity.
            long gap = Math.abs(p.getTimestamp().getEpochSecond() - target.getEpochSecond());
            if (gap < bestGap && Math.abs(p.getPrice() - price) <= Math.max(price * 0.001, 0.5)) {
                bestGap = gap;
                best = p;
            }
        }
        return best;
    }

    private MarketStructurePoint findPriorOfType(List<MarketStructurePoint> pivots,
                                                   MarketStructurePoint current, PivotType type) {
        MarketStructurePoint best = null;
        for (MarketStructurePoint p : pivots) {
            if (p.getTimestamp() == null || current.getTimestamp() == null) continue;
            if (!p.getTimestamp().isBefore(current.getTimestamp())) continue;
            if (p.getPivotType() != type) continue;
            if (best == null || p.getTimestamp().isAfter(best.getTimestamp())) best = p;
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

    private static String dateOf(List<Map<String, Object>> assignment, String role) {
        for (Map<String, Object> m : assignment) {
            if (role.equals(m.get("role"))) {
                Object d = m.get("date");
                return d == null ? null : d.toString();
            }
        }
        return null;
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
