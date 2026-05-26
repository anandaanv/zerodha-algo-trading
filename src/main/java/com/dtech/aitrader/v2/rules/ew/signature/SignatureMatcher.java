package com.dtech.aitrader.v2.rules.ew.signature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic admission/invalidation matcher. Most signature rules don't need custom logic — they
 * just declare a signature and let this matcher decide admission via the standard partial-match
 * rule per SPEC reframe ({@code 159ba913}).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>For each leg position, compare {@code observed[i].character} against
 *       {@code expected[i]}:
 *     <ul>
 *       <li>{@link LegCharacter#INDETERMINATE} observed → leg not yet observable; doesn't
 *           contribute to match or contradiction.</li>
 *       <li>observed == expected → counts as matched.</li>
 *       <li>observed != expected (both determinate) → CONTRADICTION at this position.</li>
 *     </ul>
 *   </li>
 *   <li>Decide:
 *     <ul>
 *       <li>Any contradiction → {@link AdmissionState#INVALIDATED}.</li>
 *       <li>Zero matches AND zero contradictions (all INDETERMINATE) →
 *           {@link AdmissionState#PENDING}.</li>
 *       <li>≥1 match, no contradictions → {@link AdmissionState#ADMITTED} (possibly partial).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Composite rules (e.g. bigger-impulse, where the signature is "any corrective signature") may
 * override {@link EwSignatureRule#evaluate} instead of using this matcher directly.
 */
public final class SignatureMatcher {

    private SignatureMatcher() {}

    public static AdmissionResult match(Signature signature, List<ObservedLeg> observed) {
        if (signature.legCount() != observed.size()) {
            throw new IllegalArgumentException(
                    "observed legs (" + observed.size() + ") must match signature length ("
                            + signature.legCount() + ")");
        }
        int matched = 0;
        int pendingLegs = 0;
        Map<String, Object> ev = new LinkedHashMap<>();
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < signature.legCount(); i++) {
            String label = signature.legLabels().get(i);
            LegCharacter expected = signature.expected().get(i);
            ObservedLeg leg = observed.get(i);
            LegCharacter actual = leg == null ? LegCharacter.INDETERMINATE : leg.character();
            if (actual == LegCharacter.INDETERMINATE) {
                pendingLegs++;
                trace.append(label).append("=PENDING ");
                continue;
            }
            if (actual == expected) {
                matched++;
                trace.append(label).append("=MATCH(").append(actual).append(") ");
                continue;
            }
            // Determinate mismatch — contradiction. Invalidate immediately.
            ev.put("contradicting_leg", label);
            ev.put("expected", expected.name());
            ev.put("observed", actual.name());
            ev.put("trace", trace.append(label).append("=").append(actual).append("≠").append(expected).toString());
            return AdmissionResult.invalidated(label,
                    label + " observed as " + actual + " but signature requires " + expected
                            + " → hypothesis INVALIDATED",
                    ev);
        }
        ev.put("matched_legs", matched);
        ev.put("pending_legs", pendingLegs);
        ev.put("trace", trace.toString().trim());
        if (matched == 0) {
            return AdmissionResult.pending(0,
                    "no observed legs determinate yet → admissible, awaiting structure",
                    ev);
        }
        return AdmissionResult.admitted(matched,
                "signature partially matched (" + matched + "/" + signature.legCount()
                        + " legs determinate, " + pendingLegs + " pending) → ADMITTED",
                ev);
    }
}
