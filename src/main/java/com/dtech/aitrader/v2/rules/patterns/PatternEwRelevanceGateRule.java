package com.dtech.aitrader.v2.rules.patterns;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.aitrader.v2.rules.confluence.TargetProximityChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-5 EW-relevance gate for PATTERN-family candidates per owner correction
 * ({@code 174fbb2a} Point 2): forming patterns surface only when EW-relevant. A pattern is
 * EW-relevant if its {@code trigger_price} sits within an ATR-band of any admitted EW signature's
 * watch or invalidation level. Patterns that fail the gate get a Pass-5 ELIMINATION firing — the
 * prior-fold drops them from any tradable VERDICT and downstream consumers ignore them.
 *
 * <p>This is NOT a downstream-filter on Pass-6 output. It runs at the same pass as signature
 * evaluation (Pass-5), so by the time {@link com.dtech.aitrader.v2.rules.synthesis.PatternVerdictSynthesisRule}
 * synthesises Pass-6 VERDICTs, EW-irrelevant patterns are already eliminated. The discipline:
 * pattern DETECTION runs cheap in Pass-2 (no EW context needed); pattern SURFACING is gated by
 * EW relevance here in Pass-5.
 *
 * <p>Per owner: "H&amp;S forming + a reversal on an S-level THAT IS ALSO A CONFIRMED ELLIOTT
 * LEVEL = an entry clue." This rule operationalises that — only patterns whose trigger sits at
 * an EW-blessed level survive to Pass-6.
 *
 * <p>Owner's principle (`19c58113` — identity-ambiguous until resolved forward): the gate
 * doesn't pick a single EW hypothesis — it accepts a pattern if ANY admitted hypothesis has a
 * nearby level. Co-live EW hypotheses each contribute their own watch/invalidation levels; a
 * pattern at any of them counts as EW-relevant.
 */
@Component
@Slf4j
public class PatternEwRelevanceGateRule implements Rule {

    public static final String RULE_ID = "PATTERN_EW_RELEVANCE_GATE";

    private static final double ATR_PROXIMITY_MULTIPLIER = 1.5;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P5_CONFIRMATION; }
    @Override public Family family() { return Family.PATTERN; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        List<Firing> patterns = priorFirings.stream()
                .filter(f -> f.getFamily() == Family.PATTERN)
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .toList();
        if (patterns.isEmpty()) return List.of();

        List<Double> ewLevels = collectAdmittedEwLevels(priorFirings);
        if (ewLevels.isEmpty()) {
            // No admitted EW hypotheses yet. Per owner: forming patterns are CLUES — without EW
            // context, no clue is meaningful. Eliminate all forming/non-confirmed candidates.
            // Confirmed patterns can still trade on their own (per 2c1fb814 — patterns are
            // tradable standalone) but they don't claim EW confluence.
            return eliminateNonConfirmed(ctx, patterns,
                    "no admitted EW hypotheses on context — forming patterns lack EW relevance");
        }

        double atr = atrOrFallback(ctx);
        if (atr <= 0) return List.of();

        List<Firing> out = new ArrayList<>();
        for (Firing pat : patterns) {
            Double trig = numberAt(pat.getPayload(), "trigger_price");
            if (trig == null) continue;
            String status = stringAt(pat.getPayload(), "status");
            boolean confirmed = status == null || "confirmed".equals(status);

            boolean nearAnyLevel = ewLevels.stream()
                    .anyMatch(lvl -> TargetProximityChecker.withinBand(trig, lvl, atr, ATR_PROXIMITY_MULTIPLIER));

            // Confirmed patterns are tradable independently (2c1fb814) — don't eliminate them
            // even if no EW level nearby; they may still emit VERDICT through pattern synthesis.
            // Forming patterns REQUIRE EW relevance per owner 174fbb2a Point 2.
            if (!confirmed && !nearAnyLevel) {
                out.add(buildElimination(ctx, pat,
                        "forming pattern not near any admitted EW level (1.5×ATR band)"));
            }
        }
        return out;
    }

    private List<Firing> eliminateNonConfirmed(SymbolContext ctx, List<Firing> patterns,
                                                 String reason) {
        List<Firing> out = new ArrayList<>();
        for (Firing pat : patterns) {
            String status = stringAt(pat.getPayload(), "status");
            boolean confirmed = status == null || "confirmed".equals(status);
            if (!confirmed) {
                out.add(buildElimination(ctx, pat, reason));
            }
        }
        return out;
    }

    private Firing buildElimination(SymbolContext ctx, Firing pattern, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eliminated_pattern_id", pattern.getId());
        payload.put("eliminated_rule_id", pattern.getRuleId());
        payload.put("eliminated_status", stringAt(pattern.getPayload(), "status"));
        payload.put("eliminated_completion_pct", pattern.getPayload().get("completion_pct"));
        payload.put("reason", reason);
        payload.put("owner_basis", "174fbb2a Point 2 — forming patterns surface only when EW-relevant");
        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.PATTERN)
                .pass(Pass.P5_CONFIRMATION)
                .firesOn(FiresOn.ELIMINATION)
                .refs(List.of(pattern.getId()))
                .priorDelta(PriorDelta.eliminate(reason, RULE_ID))
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }

    /**
     * Collect every admitted EW signature firing's watch + invalidation levels. These are the
     * canonical EW levels at the structure degree. Patterns near any of these count as
     * EW-relevant.
     */
    @SuppressWarnings("unchecked")
    private static List<Double> collectAdmittedEwLevels(List<Firing> priorFirings) {
        List<Double> levels = new ArrayList<>();
        for (Firing f : priorFirings) {
            if (f.getFamily() != Family.EW) continue;
            if (!"EW_SIGNATURE_EVALUATION".equals(f.getRuleId())) continue;
            if (f.getPayload() == null) continue;
            if (!"ADMITTED".equals(f.getPayload().get("admission_state"))) continue;
            Object derived = f.getPayload().get("derived_levels");
            if (!(derived instanceof Map<?, ?> dm)) continue;
            Map<String, Object> derivedMap = (Map<String, Object>) dm;
            addPricesFrom(derivedMap.get("watch"), levels);
            addPricesFrom(derivedMap.get("invalidation"), levels);
        }
        return levels;
    }

    @SuppressWarnings("unchecked")
    private static void addPricesFrom(Object list, List<Double> out) {
        if (!(list instanceof List<?> l)) return;
        for (Object o : l) {
            if (o instanceof Map<?, ?> m) {
                Object p = ((Map<String, Object>) m).get("price");
                if (p instanceof Number n) out.add(n.doubleValue());
            }
        }
    }

    private static double atrOrFallback(SymbolContext ctx) {
        // Pattern firings often carry their own atr_at_detection/atr_at_breakdown — but the gate
        // operates across mixed-source firings, so we use the structure TF ATR if available.
        if (ctx.getIndicators() != null && ctx.getSeries() != null
                && ctx.getSeries().getBarCount() > 0) {
            return ctx.getIndicators().atr(ctx.getSeries().getEndIndex());
        }
        return 0.0;
    }

    private static Double numberAt(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static String stringAt(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
