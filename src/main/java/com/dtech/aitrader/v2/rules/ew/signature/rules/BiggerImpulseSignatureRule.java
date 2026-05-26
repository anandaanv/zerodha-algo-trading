package com.dtech.aitrader.v2.rules.ew.signature.rules;

import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.aitrader.v2.rules.ew.signature.AdmissionResult;
import com.dtech.aitrader.v2.rules.ew.signature.AdmissionState;
import com.dtech.aitrader.v2.rules.ew.signature.DerivedLevels;
import com.dtech.aitrader.v2.rules.ew.signature.EwSignatureRule;
import com.dtech.aitrader.v2.rules.ew.signature.LegCharacter;
import com.dtech.aitrader.v2.rules.ew.signature.ObservedLeg;
import com.dtech.aitrader.v2.rules.ew.signature.PriceLevel;
import com.dtech.aitrader.v2.rules.ew.signature.Signature;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Composite rule per owner's worked example (reframe {@code 159ba913}).</b>
 *
 * <p>Structure: ATH → ABC down → impulse up → second down attempt makes ANOTHER ABC (fails to
 * impulse). The FAILURE to make an impulse on the second down-move opens the possibility of a
 * BIGGER IMPULSE — the whole observed thing is corrective at a higher degree → larger move
 * coming.
 *
 * <p>Governing rule: a CORRECTION's legs follow corrective signatures (5-3-5 / 3-3-5 / 3-3-3-3-3
 * / 3-3-3). The bigger-impulse-after-correction possibility is ADMITTED while the observed legs
 * match a corrective signature. INVALIDATED by observing a 3-5 — an impulsive 5 where the
 * corrective signature requires a 3 (or vice versa).
 *
 * <p>Owner's exact direction: "the structure has to be exactly opposite of a correction; a 3-5
 * where corrective is required kills it." Encoded: any observed leg whose character contradicts
 * every known corrective signature at that position removes the bigger-impulse possibility.
 *
 * <p>This rule does NOT have a fixed signature in the standard sense — it's a META rule that
 * tracks "are observed legs collectively consistent with SOME corrective signature?" PENDING
 * until enough legs are determinate; INVALIDATED on the first observed leg that no corrective
 * signature would accept at that position.
 *
 * <p><b>Provisional vocabulary note (for impl-response):</b> this rule exposes the limitation of
 * the {3, FIVE, INDETERMINATE} vocabulary — it needs to ask "is this leg's character consistent
 * with ANY corrective signature?" rather than match a single declared signature. Until vocab
 * extends (e.g. an "any of {THREE, ...}" position type), composite rules carry custom logic.
 */
@Component
public final class BiggerImpulseSignatureRule implements EwSignatureRule {

    /** Corrective signatures the bigger-impulse hypothesis stays consistent with. */
    private static final List<List<LegCharacter>> CORRECTIVE_SIGNATURES = List.of(
            List.of(LegCharacter.FIVE, LegCharacter.THREE, LegCharacter.FIVE),                    // zigzag
            List.of(LegCharacter.THREE, LegCharacter.THREE, LegCharacter.FIVE),                   // flat
            List.of(LegCharacter.THREE, LegCharacter.THREE, LegCharacter.THREE,
                    LegCharacter.THREE, LegCharacter.THREE)                                       // triangle
    );

    /** Conceptual signature for telemetry — actual evaluation uses the multi-signature check. */
    private static final Signature SIG = new Signature(
            "bigger-impulse",
            List.of("[any-corrective-signature]"),
            List.of(LegCharacter.INDETERMINATE));

    @Override public String id() { return "bigger-impulse-composite"; }
    @Override public String formName() { return "bigger-impulse"; }
    @Override public Signature signature() { return SIG; }

    @Override
    public AdmissionResult evaluate(List<ObservedLeg> observed) {
        if (observed.isEmpty()) {
            return AdmissionResult.pending(0,
                    "no observed legs — admissible as a possibility, awaiting structure",
                    Map.of("note", "bigger-impulse admitted-by-default until contradicted"));
        }
        // For each position i with a determinate observed character, check if AT LEAST ONE
        // corrective signature has the same character at position i. If NO corrective signature
        // accepts this character at this position → invalidation.
        int determinate = 0;
        for (int i = 0; i < observed.size(); i++) {
            ObservedLeg leg = observed.get(i);
            if (leg == null || leg.character() == LegCharacter.INDETERMINATE) continue;
            determinate++;
            boolean anyAccepts = false;
            for (List<LegCharacter> sig : CORRECTIVE_SIGNATURES) {
                if (i < sig.size() && sig.get(i) == leg.character()) {
                    anyAccepts = true;
                    break;
                }
            }
            if (!anyAccepts) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("contradicting_leg", leg.label());
                ev.put("contradicting_leg_position", i);
                ev.put("observed_character", leg.character().name());
                ev.put("note", "no corrective signature accepts " + leg.character() + " at position " + i
                        + " (legs index 0); the observed structure cannot be a correction → "
                        + "bigger-impulse possibility KILLED");
                return AdmissionResult.invalidated(leg.label(),
                        "Per owner's example: a " + leg.character() + " where corrective is required kills the bigger-impulse possibility. Leg "
                                + leg.label() + " observed as " + leg.character() + ", no corrective signature admits this position.",
                        ev);
            }
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("determinate_legs", determinate);
        ev.put("corrective_signatures_consulted", CORRECTIVE_SIGNATURES.size());
        ev.put("note", "all determinate observed legs match at least one corrective signature → "
                + "bigger-impulse remains admissible");
        if (determinate == 0) {
            return AdmissionResult.pending(0,
                    "no determinate observed legs yet — admissible as a possibility",
                    ev);
        }
        return AdmissionResult.admitted(determinate,
                "observed structure consistent with a correction (" + determinate
                        + " determinate legs accepted by ≥1 corrective signature) → bigger-impulse possibility LIVE",
                ev);
    }

    @Override
    public DerivedLevels deriveLevels(List<ObservedLeg> observed, SymbolContext ctx) {
        // Bigger-impulse's invalidation: the FIRST observed determinate leg that contradicts
        // every corrective signature kills it. We can't price-predict that contradiction directly
        // (it's a structural-character event, not a price level). What we CAN price-predict:
        // the bigger-impulse's directional implication is OPPOSITE the macro anchor — if anchor
        // is a HIGH (corrective down expected), bigger-impulse implies LARGER eventual MOVE UP
        // (after the correction resolves). So watch = above the macro anchor (= A_start).
        if (observed.isEmpty() || observed.get(0).endPrice() == null) {
            return new DerivedLevels(List.of(), List.of());
        }
        ObservedLeg first = observed.get(0);
        double aStart = first.startPrice();
        double aEnd = first.endPrice();
        boolean correctionDown = aStart > aEnd;
        List<PriceLevel> watch = List.of(
                new PriceLevel(aStart,
                        correctionDown ? "above A_start activates bigger-impulse UP" : "below A_start activates bigger-impulse DOWN",
                        "bigger-impulse confirms when correction resolves and price breaks back through the macro anchor"));
        // Invalidation isn't a price level — it's a structural event (observing a 3-5
        // contradiction in subsequent legs). Surface a placeholder PriceLevel referencing the
        // structural-event nature.
        List<PriceLevel> invalidation = List.of(
                new PriceLevel(Double.NaN,
                        "structural: observing a leg of WRONG character for corrective",
                        "any observed leg of impulsive/corrective character that contradicts every corrective signature at its position kills bigger-impulse"));
        return new DerivedLevels(watch, invalidation);
    }
}
