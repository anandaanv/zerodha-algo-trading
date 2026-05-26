package com.dtech.aitrader.v2.rules.confluence;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.IndicatorAccessor;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pass-5 cross-family confluence rule — EXHAUSTION-AT-TARGET (owner memo
 * {@code 34418c54}, brainstorm-Q1..Q5).
 *
 * <p>For each ADMITTED EW signature hypothesis (zigzag / truncated-c / impulse / etc.), this
 * rule checks each target level in its derived_levels.watch[] for a proximity hit (current price
 * within 1.5×ATR), then looks for two kinds of evidence at that level:
 *
 * <ul>
 *   <li>LTF momentum exhaustion (RSI divergence + MACD-histogram contraction) — supplied by
 *       {@link ExhaustionDetector}.</li>
 *   <li>Reversal-pattern firings (Family.PATTERN, FiresOn.CANDIDATE) whose {@code trigger_price}
 *       sits within the same ATR band of the same target.</li>
 * </ul>
 *
 * <p>The output is a CONFIRMATION firing carrying a deterministic {@code tilt_score} ∈ [0, 1] and
 * a {@code tilt_direction} (REVERSAL | CONTINUATION). The tilt's prior_delta is
 * {@code GRADUATED(0.0, ...)} — per SPEC reframe ({@code 159ba913}) validity is NOT a weight; the
 * tilt is used by Pass-6 to ORDER the level-map, never to ELIMINATE a hypothesis.
 *
 * <p>When a REVERSAL tilt fires AND a reversal pattern is the trigger, the rule additionally
 * emits a secondary WATCH firing carrying an {@code invalidation_stamp} for the CONTINUATION
 * hypothesis at "beyond target" — owner Q5: the pattern's geometry tells us where the continuation
 * is invalidated if the reversal asserts.
 */
@Component
@Slf4j
public class EwExhaustionAtTargetRule implements Rule {

    public static final String RULE_ID = "EW_EXHAUSTION_AT_TARGET";

    private static final double ATR_MULTIPLIER = TargetProximityChecker.DEFAULT_ATR_MULTIPLIER;

    // Tilt-score weights — deterministic, evidence-derived per owner Q4 position.
    private static final double TILT_BASE_PROXIMITY = 0.34;
    private static final double TILT_EXHAUSTION = 0.33;
    private static final double TILT_REVERSAL_PATTERN = 0.33;

    private static final List<String> TILT_BASIS = List.of(
            "Q1: pass-5 cross-family rule (reads PATTERN + signature firings); NOT an EwSignatureRule — no DSL counter reset",
            "Q2: exhaustion = RSI divergence + MACD-histogram contraction (PHASE-A: structure TF; LTF map deferred)",
            "Q3: cross-family — pattern firings consumed by trigger_price proximity (first real cross-family confluence)",
            "Q4: tilt_score is evidence-derived (proximity + exhaustion + reversal_pattern), capped 1.0; PriorDelta=0.0 → ordering only, never eliminates",
            "Q5: REVERSAL + pattern emits secondary WATCH firing with invalidation_stamp beyond target");

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P5_CONFIRMATION; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        IndicatorAccessor accessor = ctx.getIndicators();
        if (accessor == null) {
            log.debug("[exhaustion-at-target] no IndicatorAccessor on context");
            return List.of();
        }
        int endIdx = accessor.series().getEndIndex();
        if (endIdx < 0) return List.of();

        Double currentPrice = mostRecentPivotPrice(ctx);
        if (currentPrice == null) {
            log.debug("[exhaustion-at-target] no pivots in context — cannot compute current price");
            return List.of();
        }
        String currentPriceSource = mostRecentPivotSource(ctx);

        double atr = accessor.atr(endIdx);
        if (atr <= 0.0) return List.of();

        List<Firing> admittedSignatures = priorFirings.stream()
                .filter(f -> f.getFamily() == Family.EW)
                .filter(f -> f.getRuleId() != null
                        && f.getRuleId().equals("EW_SIGNATURE_EVALUATION"))
                .filter(f -> f.getPayload() != null
                        && "ADMITTED".equals(f.getPayload().get("admission_state")))
                .toList();
        if (admittedSignatures.isEmpty()) return List.of();

        // PHASE-A discipline (owner spec SPEC-008 e332be7f cross-cutting + Q8 from 77cd09ee
        // addendum): tilt is contributed only by CONFIRMED reversal patterns until owner ratifies
        // the completion_pct → tilt-magnitude formula (proposed in decisions-needed memo
        // 5a2a7efa). Forming-stage firings are acknowledged (carried in `all_nearby_patterns` for
        // audit) but do not contribute to tilt_score. Status absent ⇒ treated as confirmed for
        // backwards compat with the original DT/DB payloads.
        List<Firing> patternCandidates = priorFirings.stream()
                .filter(f -> f.getFamily() == Family.PATTERN)
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .filter(f -> {
                    String status = stringAt(f.getPayload(), "status");
                    return status == null || "confirmed".equals(status);
                })
                .toList();

        List<Firing> out = new ArrayList<>();
        for (Firing sig : admittedSignatures) {
            List<Map<String, Object>> watchLevels = extractWatchLevels(sig);
            if (watchLevels.isEmpty()) continue;

            for (Map<String, Object> target : watchLevels) {
                Double targetPrice = numberAt(target, "price");
                if (targetPrice == null) continue;

                if (!TargetProximityChecker.withinBand(currentPrice, targetPrice, atr, ATR_MULTIPLIER)) {
                    continue;
                }

                String hypothesisDirection = targetPrice > currentPrice ? "LONG" : "SHORT";

                List<Firing> nearbyPatterns = patternsNearTarget(patternCandidates, targetPrice, atr);
                List<Firing> reversalPatterns = filterReversalPatterns(nearbyPatterns, hypothesisDirection);
                List<ExhaustionSignal> exhaustion = collectExhaustion(ctx, accessor, endIdx,
                        hypothesisDirection);
                List<ExhaustionSignal> reversalExhaustion = filterReversalExhaustion(exhaustion,
                        hypothesisDirection);

                boolean hasReversalPattern = !reversalPatterns.isEmpty();
                boolean hasReversalExhaustion = !reversalExhaustion.isEmpty();

                double tiltScore = TILT_BASE_PROXIMITY
                        + (hasReversalExhaustion ? TILT_EXHAUSTION : 0.0)
                        + (hasReversalPattern ? TILT_REVERSAL_PATTERN : 0.0);
                if (tiltScore > 1.0) tiltScore = 1.0;

                String tiltDirection = (hasReversalPattern || hasReversalExhaustion)
                        ? "REVERSAL" : "CONTINUATION";

                Firing mainFiring = buildMainTiltFiring(ctx, sig, target, targetPrice,
                        currentPrice, currentPriceSource, atr, hypothesisDirection,
                        tiltDirection, tiltScore, reversalExhaustion, reversalPatterns,
                        exhaustion, nearbyPatterns);
                out.add(mainFiring);

                if ("REVERSAL".equals(tiltDirection) && hasReversalPattern) {
                    out.add(buildInvalidationStampFiring(ctx, sig, target, targetPrice,
                            hypothesisDirection, reversalPatterns));
                }
            }
        }
        return out;
    }

    private Firing buildMainTiltFiring(SymbolContext ctx, Firing sig, Map<String, Object> target,
                                         double targetPrice, double currentPrice,
                                         String currentPriceSource, double atr,
                                         String hypothesisDirection, String tiltDirection,
                                         double tiltScore,
                                         List<ExhaustionSignal> reversalExhaustion,
                                         List<Firing> reversalPatterns,
                                         List<ExhaustionSignal> allExhaustion,
                                         List<Firing> allNearbyPatterns) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("signature_firing_ref", sig.getId());
        payload.put("hypothesis_form", sig.getPayload().get("form_name"));
        payload.put("hypothesis_direction", hypothesisDirection);
        payload.put("target_level", serializeTarget(target, targetPrice));
        payload.put("current_price", currentPrice);
        payload.put("current_price_source", currentPriceSource);
        payload.put("atr_band", atr * ATR_MULTIPLIER);
        payload.put("proximity_hit", true);
        payload.put("exhaustion_signals", serializeExhaustion(reversalExhaustion));
        payload.put("reversal_patterns", serializePatterns(reversalPatterns, targetPrice));
        payload.put("all_exhaustion_seen", serializeExhaustion(allExhaustion));
        payload.put("all_nearby_patterns", serializePatterns(allNearbyPatterns, targetPrice));
        payload.put("tilt_direction", tiltDirection);
        payload.put("tilt_score", tiltScore);
        payload.put("tilt_basis", TILT_BASIS);
        payload.put("phase", "PHASE_A");

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P5_CONFIRMATION)
                .firesOn(FiresOn.CONFIRMATION)
                .refs(List.of(sig.getId()))
                .priorDelta(PriorDelta.graduated(0.0,
                        "evidence-derived tilt (no ranking side-effect — see SPEC reframe 159ba913)",
                        RULE_ID))
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }

    private Firing buildInvalidationStampFiring(SymbolContext ctx, Firing sig,
                                                  Map<String, Object> target, double targetPrice,
                                                  String hypothesisDirection,
                                                  List<Firing> reversalPatterns) {
        // Owner Q5: the continuation hypothesis IS the current hypothesis (the one we sit at the
        // target of). If the reversal asserts, the continuation is invalidated beyond X.
        String beyondDirection = "LONG".equals(hypothesisDirection) ? "above" : "below";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invalidation_stamp", true);
        payload.put("for_continuation_signature_ref", sig.getId());
        payload.put("for_hypothesis_form", sig.getPayload().get("form_name"));
        payload.put("for_hypothesis_direction", hypothesisDirection);
        payload.put("beyond_level", targetPrice);
        payload.put("beyond_direction", beyondDirection);
        payload.put("source", "reversal-pattern-at-target");
        payload.put("pattern_firing_refs",
                reversalPatterns.stream().map(Firing::getId).toList());
        payload.put("target_level", serializeTarget(target, targetPrice));
        payload.put("tilt_basis", TILT_BASIS);

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P5_CONFIRMATION)
                .firesOn(FiresOn.WATCH)
                .refs(List.of(sig.getId()))
                .priorDelta(PriorDelta.graduated(0.0,
                        "invalidation stamp — annotation, not ranking",
                        RULE_ID))
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractWatchLevels(Firing sig) {
        Object derived = sig.getPayload().get("derived_levels");
        if (!(derived instanceof Map<?, ?> dm)) return List.of();
        Object watch = ((Map<String, Object>) dm).get("watch");
        if (!(watch instanceof List<?> wl)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(wl.size());
        for (Object o : wl) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private static List<Firing> patternsNearTarget(List<Firing> patterns, double targetPrice,
                                                     double atr) {
        List<Firing> out = new ArrayList<>();
        for (Firing pat : patterns) {
            Double trig = numberAt(pat.getPayload(), "trigger_price");
            if (trig == null) continue;
            if (TargetProximityChecker.withinBand(trig, targetPrice, atr, ATR_MULTIPLIER)) {
                out.add(pat);
            }
        }
        return out;
    }

    private static List<Firing> filterReversalPatterns(List<Firing> nearby,
                                                         String hypothesisDirection) {
        List<Firing> out = new ArrayList<>();
        for (Firing pat : nearby) {
            String bias = stringAt(pat.getPayload(), "bias");
            if (bias == null) continue;
            // A reversal pattern is one whose bias OPPOSES the continuation hypothesis: a DT
            // (SHORT) at a LONG target tells us up-continuation could be ending; a DB (LONG) at a
            // SHORT target tells us down-continuation could be ending.
            if ("LONG".equals(hypothesisDirection) && "SHORT".equalsIgnoreCase(bias)) out.add(pat);
            else if ("SHORT".equals(hypothesisDirection) && "LONG".equalsIgnoreCase(bias)) out.add(pat);
        }
        return out;
    }

    private static List<ExhaustionSignal> collectExhaustion(SymbolContext ctx,
                                                              IndicatorAccessor accessor, int endIdx,
                                                              String hypothesisDirection) {
        List<ExhaustionSignal> signals = new ArrayList<>();
        // RSI divergence between the two most-recent structure-TF pivots — PHASE-A approximation
        // of "two swings approaching the target".
        List<MarketStructurePoint> pivots = ctx.getPivots();
        if (pivots != null && pivots.size() >= 2) {
            MarketStructurePoint first = pivots.get(pivots.size() - 2);
            MarketStructurePoint second = pivots.get(pivots.size() - 1);
            Integer firstIdx = barIndexAt(accessor, first.getTimestamp());
            Integer secondIdx = barIndexAt(accessor, second.getTimestamp());
            if (firstIdx != null && secondIdx != null) {
                boolean priceMovedUp = "LONG".equals(hypothesisDirection);
                Optional<ExhaustionSignal> rsiSig = ExhaustionDetector.detectRsiDivergence(
                        accessor, firstIdx, secondIdx, priceMovedUp, ctx.getTf());
                rsiSig.ifPresent(signals::add);
            }
        }
        Optional<ExhaustionSignal> macdSig = ExhaustionDetector.detectMacdHistogramContraction(
                accessor, endIdx, ctx.getTf(), hypothesisDirection);
        macdSig.ifPresent(signals::add);
        return signals;
    }

    private static List<ExhaustionSignal> filterReversalExhaustion(List<ExhaustionSignal> all,
                                                                     String hypothesisDirection) {
        String expectedTilt = "LONG".equals(hypothesisDirection) ? "BEARISH" : "BULLISH";
        List<ExhaustionSignal> out = new ArrayList<>();
        for (ExhaustionSignal s : all) {
            if (expectedTilt.equals(s.direction())) out.add(s);
        }
        return out;
    }

    private static Integer barIndexAt(IndicatorAccessor accessor, Instant ts) {
        if (ts == null) return null;
        var series = accessor.series();
        for (int i = series.getEndIndex(); i >= series.getBeginIndex(); i--) {
            if (ts.equals(series.getBar(i).getEndTime())) return i;
        }
        return null;
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

    private static Map<String, Object> serializeTarget(Map<String, Object> target,
                                                         double targetPrice) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("price", targetPrice);
        m.put("label", target.get("label"));
        m.put("basis", target.get("basis"));
        return m;
    }

    private static List<Map<String, Object>> serializeExhaustion(List<ExhaustionSignal> signals) {
        List<Map<String, Object>> out = new ArrayList<>(signals.size());
        for (ExhaustionSignal s : signals) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", s.kind());
            m.put("tf", s.tf());
            m.put("direction", s.direction());
            m.put("evidence", s.evidence());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> serializePatterns(List<Firing> patterns,
                                                                 double targetPrice) {
        List<Map<String, Object>> out = new ArrayList<>(patterns.size());
        for (Firing p : patterns) {
            Double trig = numberAt(p.getPayload(), "trigger_price");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("firing_id", p.getId());
            m.put("rule_id", p.getRuleId());
            m.put("trigger_price", trig);
            m.put("bias", stringAt(p.getPayload(), "bias"));
            m.put("distance_to_target",
                    trig == null ? null : TargetProximityChecker.distance(trig, targetPrice));
            out.add(m);
        }
        return out;
    }
}
