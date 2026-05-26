package com.dtech.aitrader.v2.rules.synthesis;

import com.dtech.aitrader.data.RuleFiring;
import com.dtech.aitrader.v2.rules.ContextProbeResult;
import com.dtech.aitrader.v2.rules.ContextSignatureBuilder;
import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.IndicatorConfluence;
import com.dtech.aitrader.v2.rules.MacroRegime;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorFold;
import com.dtech.aitrader.v2.rules.Role;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SrPosition;
import com.dtech.aitrader.v2.rules.SymbolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pass-6 synthesis: folds each Pass-2 candidate's chain, picks the survivors whose live_prior
 * clears the threshold, and emits one {@link FiresOn#VERDICT} firing per survivor.
 *
 * <p>The VERDICT is the ONLY outcome-bearing firing (Q7 in convergence memo {@code 9c60e777}).
 * Trigger / invalidation / target are lifted verbatim from the candidate's payload; the
 * {@code context_signature} is built from the candidate's role + the probe. Cross-family
 * confluence (≥2 families contributing firings to a candidate's chain) is marked in the payload
 * and used to label the signature with a {@code CONFLUENT} prefix — so eval can compare
 * confluent vs solo firings.
 */
@Component
@Slf4j
public class PatternVerdictSynthesisRule implements Rule {

    public static final String RULE_ID = "PATTERN_VERDICT_SYNTHESIS";

    /** Live prior threshold — survivor must clear this for the synthesis to emit a VERDICT. */
    private static final double PRIOR_THRESHOLD = 0.30;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P6_SYNTHESIS; }
    @Override public Family family() { return Family.SYNTHESIS; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        // Only CONFIRMED pattern candidates are tradable per SPEC-008 (e332be7f): "A forming
        // pattern TILTS, a confirmed pattern is tradable." Forming firings (status="forming") are
        // surfaced for cross-family confluence consumption but NOT synthesized into a VERDICT.
        // Backwards compat: missing status ⇒ treat as confirmed (DT/DB pre-retrofit).
        List<Firing> candidates = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .filter(PatternVerdictSynthesisRule::isConfirmedOrAbsentStatus)
                .toList();
        if (candidates.isEmpty()) return List.of();

        List<Firing> verdicts = new ArrayList<>();
        for (Firing cand : candidates) {
            PriorFold.Result folded = PriorFold.foldChain(cand, priorFirings);
            if (folded.eliminated()) continue;
            if (folded.livePrior() < PRIOR_THRESHOLD) continue;
            Firing verdict = buildVerdict(ctx, cand, folded, priorFirings);
            if (verdict != null) verdicts.add(verdict);
        }
        return verdicts;
    }

    private Firing buildVerdict(SymbolContext ctx, Firing candidate, PriorFold.Result folded,
                                  List<Firing> priorFirings) {
        Map<String, Object> p = candidate.getPayload();
        if (p == null) return null;

        Double triggerPrice = numberOrNull(p.get("trigger_price"));
        Double invalidation = numberOrNull(p.get("invalidation_price"));
        Double target = numberOrNull(p.get("target_price"));
        if (triggerPrice == null || invalidation == null) return null;

        String biasStr = (String) p.getOrDefault("bias", "NEUTRAL");
        RuleFiring.Bias bias = RuleFiring.Bias.valueOf(biasStr);

        // Cross-family confluence — which families contributed firings to this candidate's chain?
        Set<Family> contributingFamilies = new LinkedHashSet<>();
        contributingFamilies.add(candidate.getFamily());  // candidate's own family
        String candId = candidate.getId();
        for (Firing f : priorFirings) {
            if (f.getRefs() != null && f.getRefs().contains(candId) && f.getFamily() != null) {
                contributingFamilies.add(f.getFamily());
            }
        }
        boolean confluent = contributingFamilies.size() >= 2;

        // Role: prefer the most recent CLASSIFICATION's role; fall back to NEUTRAL.
        Role role = resolveRole(priorFirings, candId);
        ContextProbeResult probe = ctx.getProbe() != null ? ctx.getProbe() : candidate.getContext();
        if (probe == null) {
            probe = ContextProbeResult.builder()
                    .macroRegime(MacroRegime.UNKNOWN)
                    .srPosition(SrPosition.UNKNOWN)
                    .indicatorConfluence(IndicatorConfluence.UNKNOWN)
                    .build();
        }

        String baseSig = ContextSignatureBuilder.build(candidate.getRuleId(), role, probe);
        String signature = confluent ? ("CONFLUENT_" + baseSig) : baseSig;

        Map<String, Object> verdictPayload = new LinkedHashMap<>();
        verdictPayload.put("source_candidate_id", candId);
        verdictPayload.put("source_rule_id", candidate.getRuleId());
        verdictPayload.put("live_prior", folded.livePrior());
        verdictPayload.put("contributing_families", contributingFamilies.stream().map(Family::name).toList());
        verdictPayload.put("confluent", confluent);

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.SYNTHESIS)
                .pass(Pass.P6_SYNTHESIS)
                .firesOn(FiresOn.VERDICT)
                .refs(List.of(candId))
                .roundNum(1)
                .bias(bias)
                .triggerPrice(triggerPrice)
                .invalidationPrice(invalidation)
                .targetPrice(target)
                .role(role)
                .contextSignature(signature)
                .finalConviction(folded.livePrior())
                .context(probe)
                .payload(verdictPayload)
                .build();
    }

    private static Role resolveRole(List<Firing> priorFirings, String candId) {
        return priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.CLASSIFICATION)
                .filter(f -> f.getRefs() != null && f.getRefs().contains(candId))
                .map(Firing::getRole)
                .filter(Objects::nonNull)
                .reduce((a, b) -> b)   // last classification's role wins
                .orElse(Role.NEUTRAL);
    }

    private static Double numberOrNull(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return null;
    }

    private static boolean isConfirmedOrAbsentStatus(Firing f) {
        Map<String, Object> p = f.getPayload();
        if (p == null) return true;
        Object status = p.get("status");
        if (status == null) return true;
        return "confirmed".equals(status.toString());
    }
}
