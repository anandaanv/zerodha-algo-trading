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
 * Pass-4 Rule 0.35 — if a candidate aligns with a weight-≥2 trader annotation (Pass-1 FACT from
 * {@link EwAnnotationIntakeRule}), emit a GRADUATED CLASSIFICATION boost.
 *
 * <p>Alignment is determined by keyword matching the annotation text against the candidate's
 * form:
 * <ul>
 *   <li>Corrective hints — "wave 4", "wave c", "wave a", "wave b", "abc", "zigzag", "flat",
 *       "triangle", "correction" — align with zigzag / flat / triangle candidates.</li>
 *   <li>Impulse hints — "wave 1", "wave 3", "wave 5", "impulse" — align with impulse candidates.</li>
 * </ul>
 *
 * <p>RELIANCE blessed reference {@code cde6bbc9}: the weight-3 "On weekly appears to be in wave 4C"
 * annotation aligns with the corrective MF1 zigzag (and NOT with the impulsive MF2). MF1 gets
 * the boost; MF2 does not.
 *
 * <p>Boost magnitude scales with annotation weight: {@code delta = base_delta × (weight/3.0)}.
 * Multiple aligning annotations → multiple firings, per Q5 one-firing-per-(candidate, delta).
 */
@Component
@Slf4j
public class EwAnnotationPriorRule implements Rule {

    public static final String RULE_ID = "EW_ANNOTATION_PRIOR";

    @Value("${rules.ew.annotation-prior.base-delta:0.05}")
    private double baseDelta = 0.05;

    // Keyword sets, lowercased.
    private static final List<String> CORRECTIVE_KEYWORDS = List.of(
            "wave 4", "wave-4", "wave a", "wave b", "wave c", "wave-c", "abc",
            "zigzag", "flat", "triangle", "correction", "corrective");
    private static final List<String> IMPULSE_KEYWORDS = List.of(
            "wave 1", "wave 3", "wave 5", "wave-3", "wave-5", "impulse", "impulsive");

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P4_CLASSIFICATION; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        List<Firing> annotations = priorFirings.stream()
                .filter(f -> f.getFamily() == Family.EW)
                .filter(f -> f.getFiresOn() == FiresOn.FACT)
                .filter(f -> EwAnnotationIntakeRule.RULE_ID.equals(f.getRuleId()))
                .toList();
        List<Firing> candidates = priorFirings.stream()
                .filter(f -> f.getFamily() == Family.EW)
                .filter(f -> f.getFiresOn() == FiresOn.CANDIDATE)
                .toList();
        if (annotations.isEmpty() || candidates.isEmpty()) return List.of();

        java.util.Set<String> eliminated = priorFirings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.ELIMINATION && f.getRefs() != null)
                .flatMap(f -> f.getRefs().stream())
                .collect(java.util.stream.Collectors.toSet());

        List<Firing> out = new ArrayList<>();
        for (Firing cand : candidates) {
            if (eliminated.contains(cand.getId())) continue;
            String form = String.valueOf(cand.getPayload().get("form")).toLowerCase();
            boolean isCorrective = isCorrective(form);
            boolean isImpulsive = "impulse".equals(form);

            for (Firing ann : annotations) {
                String text = String.valueOf(ann.getPayload().get("text")).toLowerCase();
                int weight = ((Number) ann.getPayload().getOrDefault("weight", 0)).intValue();

                boolean correctiveMatch = isCorrective && containsAny(text, CORRECTIVE_KEYWORDS);
                boolean impulsiveMatch = isImpulsive && containsAny(text, IMPULSE_KEYWORDS);
                if (!correctiveMatch && !impulsiveMatch) continue;

                double delta = baseDelta * (weight / 3.0);
                String matchedKind = correctiveMatch ? "corrective" : "impulsive";
                out.add(emitAlignment(ctx, cand, ann, matchedKind, weight, delta));
            }
        }
        return out;
    }

    private boolean isCorrective(String form) {
        return "zigzag".equals(form) || "flat".equals(form)
                || "expanded_flat".equals(form) || "triangle".equals(form)
                || "wxy".equals(form) || "wxyxz".equals(form);
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    private Firing emitAlignment(SymbolContext ctx, Firing candidate, Firing annotation,
                                   String matchedKind, int weight, double delta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("annotation_ref", annotation.getId());
        payload.put("annotation_weight", weight);
        payload.put("annotation_text",
                String.valueOf(annotation.getPayload().get("text")));
        payload.put("matched_kind", matchedKind);
        payload.put("candidate_form", candidate.getPayload().get("form"));

        String reason = "annotation (weight " + weight + ", " + matchedKind
                + ") aligns with candidate form " + candidate.getPayload().get("form");

        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P4_CLASSIFICATION)
                .firesOn(FiresOn.CLASSIFICATION)
                .refs(List.of(candidate.getId()))
                .priorDelta(PriorDelta.graduated(delta, reason, "0.35"))
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }
}
