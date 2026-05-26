package com.dtech.aitrader.v2.rules.ew.signature;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-5 orchestrator: runs every registered {@link EwSignatureRule} against every surviving EW
 * candidate. For each (candidate, rule) pair it derives observed legs from the candidate's
 * {@code pivot_assignment}, computes each leg's character via {@link LegCharacterExaminer}, and
 * emits a CONFIRMATION firing carrying the {@link AdmissionResult} + {@link DerivedLevels} for
 * Pass-6 to fold into the level-map.
 *
 * <p>Per SPEC reframe ({@code 159ba913}): all admissible hypotheses are ENUMERATED, kept LIVE
 * until invalidated. The orchestrator is the entry point that lets the framework grow via
 * pluggable signature rules (Spring auto-wires the {@code List<EwSignatureRule>}).
 */
@Component
@Slf4j
public class EwSignatureEvaluationRule implements Rule {

    public static final String RULE_ID = "EW_SIGNATURE_EVALUATION";

    private final List<EwSignatureRule> signatureRules;

    public EwSignatureEvaluationRule(List<EwSignatureRule> signatureRules) {
        this.signatureRules = signatureRules == null ? List.of() : signatureRules;
    }

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P5_CONFIRMATION; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        if (signatureRules.isEmpty()) return List.of();

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
            // Derive observed legs once per candidate. Honour SPEC-006 wave-completion state —
            // if a leg's terminal role is IN_PROGRESS / CANDIDATE, the leg's character is
            // INDETERMINATE (we only know what we've seen so far). Owner's blessed read
            // (ICICIBANK e409cb9e): B is in progress, only B.A formed — character of the FULL B
            // is unknown; observed leg should not be treated as a complete FIVE/THREE.
            List<ObservedLeg> baseLegs = deriveObservedLegs(cand, ctx, priorFirings);
            if (baseLegs.isEmpty()) continue;

            for (EwSignatureRule rule : signatureRules) {
                List<ObservedLeg> padded = padObservedToSignature(baseLegs, rule.signature(), cand);
                AdmissionResult result = rule.evaluate(padded);
                DerivedLevels levels = (result.state() == AdmissionState.ADMITTED)
                        ? rule.deriveLevels(padded, ctx)
                        : new DerivedLevels(List.of(), List.of());

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("signature_rule_id", rule.id());
                payload.put("form_name", rule.formName());
                payload.put("admission_state", result.state().name());
                payload.put("matched_legs_count", result.matchedLegsCount());
                payload.put("contradicting_leg", result.contradictingLeg());
                payload.put("reasoning", result.reasoning());
                payload.put("evidence", result.evidence());
                payload.put("observed_legs", serializeObservedLegs(padded));
                payload.put("derived_levels", serializeDerivedLevels(levels));
                payload.put("provisional", true);
                payload.put("provisional_note",
                        "PHASE-A leg-character bridge (retest + retrace); pattern-shape PHASE-B "
                                + "(SPEC reframe 159ba913) will supersede.");

                // Prior delta: tiny — these firings exist for ADMISSION/INVALIDATION semantics,
                // not for prior-fold ranking. Per owner reframe: validity is no-invalidation-yet,
                // not a weight. Use a neutral delta (0.0) so PriorFold treats this as an audit
                // record without re-introducing the ranking model.
                PriorDelta delta = (result.state() == AdmissionState.INVALIDATED)
                        ? PriorDelta.graduated(-0.05, result.reasoning(), rule.id())
                        : PriorDelta.graduated(0.0, result.reasoning(), rule.id());

                out.add(Firing.builder()
                        .ruleId(RULE_ID)
                        .symbol(ctx.getSymbol())
                        .tf(ctx.getTf())
                        .asOf(ctx.getAsOf())
                        .family(Family.EW)
                        .pass(Pass.P5_CONFIRMATION)
                        .firesOn(FiresOn.CONFIRMATION)
                        .refs(List.of(cand.getId()))
                        .priorDelta(delta)
                        .roundNum(1)
                        .payload(payload)
                        .context(ctx.getProbe())
                        .build());
            }
        }
        return out;
    }

    /**
     * Derive observed legs from the candidate's pivot_assignment. Honour SPEC-006 wave-completion
     * state — a leg whose terminal role is not COMPLETE has indeterminate character (we only see
     * partial; B.A formed does not mean B as a whole is FIVE).
     */
    @SuppressWarnings("unchecked")
    private List<ObservedLeg> deriveObservedLegs(Firing candidate, SymbolContext ctx,
                                                   List<Firing> priorFirings) {
        Map<String, Object> p = candidate.getPayload();
        String form = (String) p.get("form");
        List<Map<String, Object>> assignment = (List<Map<String, Object>>) p.get("pivot_assignment");
        if (assignment == null || form == null) return List.of();

        List<ObservedLeg> legs = new ArrayList<>();
        if ("zigzag".equals(form)) {
            legs.add(buildLeg(assignment, "A_start", "A_end", "A", ctx, candidate, priorFirings));
            legs.add(buildLeg(assignment, "A_end", "B_end", "B", ctx, candidate, priorFirings));
            legs.add(buildLeg(assignment, "B_end", null, "C", ctx, candidate, priorFirings));
        } else if ("impulse".equals(form)) {
            legs.add(buildLeg(assignment, "W0", "W1_end", "W1", ctx, candidate, priorFirings));
            legs.add(buildLeg(assignment, "W1_end", null, "W2", ctx, candidate, priorFirings));
        }
        return legs;
    }

    private ObservedLeg buildLeg(List<Map<String, Object>> assignment,
                                   String startRole, String endRole, String legLabel,
                                   SymbolContext ctx, Firing candidate, List<Firing> priorFirings) {
        Double startPrice = priceOf(assignment, startRole);
        String startDate = dateOf(assignment, startRole);
        Double endPrice = endRole == null ? null : priceOf(assignment, endRole);
        String endDate = endRole == null ? null : dateOf(assignment, endRole);

        if (startPrice == null || startDate == null) {
            return new ObservedLeg(legLabel, null, 0.0, null, null,
                    LegCharacter.INDETERMINATE, Map.of("note", "start role missing on candidate"));
        }
        if (endPrice == null || endDate == null) {
            return new ObservedLeg(legLabel, startDate, startPrice, null, null,
                    LegCharacter.INDETERMINATE,
                    Map.of("note", "leg incomplete (no end pivot yet) — character indeterminate"));
        }

        // Honour SPEC-006 wave-completion state — if the leg's END role is not COMPLETE, treat
        // the leg's character as INDETERMINATE. Owner's blessed ICICIBANK (e409cb9e) is the
        // exact case: B is IN_PROGRESS, B.A formed; the FULL B's character is unknown.
        String endRoleState = resolveRoleState(endRole, assignment, candidate, priorFirings);
        if (endRoleState != null && !"COMPLETE".equals(endRoleState)) {
            return new ObservedLeg(legLabel, startDate, startPrice, endDate, endPrice,
                    LegCharacter.INDETERMINATE,
                    Map.of("note",
                            "end role " + endRole + " state=" + endRoleState
                                    + " — partial leg character indeterminate per SPEC-006",
                            "end_role_state", endRoleState));
        }

        LegCharacterExaminer.Result res = LegCharacterExaminer.examine(
                ctx, startDate, startPrice, endDate, endPrice, "OneHour");
        return new ObservedLeg(legLabel, startDate, startPrice, endDate, endPrice,
                res.character(), res.evidence());
    }

    /**
     * Resolve a role's CURRENT state, walking EwWaveCompletionRule firings for the latest
     * promotion/demotion of this role's state. Falls back to the role's initial state in the
     * candidate's pivot_assignment (e.g. {@code "CANDIDATE"} for B_end at enumeration time).
     */
    private static String resolveRoleState(String role, List<Map<String, Object>> assignment,
                                             Firing candidate, List<Firing> priorFirings) {
        if (role == null) return null;
        Firing latest = null;
        for (Firing f : priorFirings) {
            if (!"EW_WAVE_COMPLETION".equals(f.getRuleId())) continue;
            if (f.getRefs() == null || !f.getRefs().contains(candidate.getId())) continue;
            if (f.getPayload() == null) continue;
            if (!role.equals(f.getPayload().get("role"))) continue;
            Object ns = f.getPayload().get("new_state");
            if (!(ns instanceof String)) continue;
            if (latest == null || isLater(f, latest)) latest = f;
        }
        if (latest != null) {
            return (String) latest.getPayload().get("new_state");
        }
        for (Map<String, Object> e : assignment) {
            if (role.equals(e.get("role"))) {
                Object st = e.get("state");
                return st == null ? null : st.toString();
            }
        }
        return null;
    }

    private static boolean isLater(Firing a, Firing b) {
        int ar = a.getRoundNum() == null ? 0 : a.getRoundNum();
        int br = b.getRoundNum() == null ? 0 : b.getRoundNum();
        if (ar != br) return ar > br;
        int ap = a.getPass() == null ? 0 : a.getPass().order;
        int bp = b.getPass() == null ? 0 : b.getPass().order;
        if (ap != bp) return ap > bp;
        return a.getId() != null && b.getId() != null && a.getId().compareTo(b.getId()) > 0;
    }

    private List<ObservedLeg> padObservedToSignature(List<ObservedLeg> base, Signature sig,
                                                       Firing candidate) {
        // Map base legs by label, then for each signature label produce an ObservedLeg (matching
        // label if present, else INDETERMINATE placeholder).
        Map<String, ObservedLeg> byLabel = new LinkedHashMap<>();
        for (ObservedLeg leg : base) {
            if (leg.label() != null) byLabel.put(leg.label(), leg);
        }
        List<ObservedLeg> padded = new ArrayList<>(sig.legCount());
        for (String legLabel : sig.legLabels()) {
            ObservedLeg matched = byLabel.get(legLabel);
            if (matched != null) {
                padded.add(matched);
            } else {
                // Signature expects this leg but candidate's pivot_assignment doesn't have it
                // yet (e.g. triangle signature wants D, E but zigzag candidate only has A, B, C).
                padded.add(new ObservedLeg(legLabel, null, 0.0, null, null,
                        LegCharacter.INDETERMINATE,
                        Map.of("note", "leg not present in candidate's pivot_assignment — padded as indeterminate")));
            }
        }
        return padded;
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

    private List<Map<String, Object>> serializeObservedLegs(List<ObservedLeg> legs) {
        List<Map<String, Object>> out = new ArrayList<>(legs.size());
        for (ObservedLeg leg : legs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", leg.label());
            m.put("start_date", leg.startDate());
            m.put("start_price", leg.startPrice());
            m.put("end_date", leg.endDate());
            m.put("end_price", leg.endPrice());
            m.put("character", leg.character().name());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> serializeDerivedLevels(DerivedLevels levels) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("watch", serializePriceLevels(levels.watch()));
        m.put("invalidation", serializePriceLevels(levels.invalidation()));
        return m;
    }

    private List<Map<String, Object>> serializePriceLevels(List<PriceLevel> levels) {
        List<Map<String, Object>> out = new ArrayList<>(levels.size());
        for (PriceLevel lv : levels) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("price", lv.price());
            m.put("label", lv.label());
            m.put("basis", lv.basis());
            out.add(m);
        }
        return out;
    }
}
