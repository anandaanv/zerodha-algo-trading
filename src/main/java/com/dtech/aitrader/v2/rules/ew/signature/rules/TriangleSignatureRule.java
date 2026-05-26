package com.dtech.aitrader.v2.rules.ew.signature.rules;

import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.aitrader.v2.rules.ew.signature.AdmissionResult;
import com.dtech.aitrader.v2.rules.ew.signature.DerivedLevels;
import com.dtech.aitrader.v2.rules.ew.signature.EwSignatureRule;
import com.dtech.aitrader.v2.rules.ew.signature.LegCharacter;
import com.dtech.aitrader.v2.rules.ew.signature.ObservedLeg;
import com.dtech.aitrader.v2.rules.ew.signature.PriceLevel;
import com.dtech.aitrader.v2.rules.ew.signature.Signature;
import com.dtech.aitrader.v2.rules.ew.signature.SignatureMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Triangle signature: <code>A=B=C=D=E=THREE</code>. All five legs corrective (overlapping
 * 3-waves).
 *
 * <p>Per owner direction (O2 in reframe {@code 159ba913}): "we are far from drawing a triangle."
 * Admission is LOOSE — fires when prior observed legs all match THREE; geometric confirmation
 * (contraction ratios, leg-to-leg shrinkage) comes only as the structure actually completes.
 * Do NOT gate admission on contraction ratios yet. The hypothesis is ADMISSIBLE because prior
 * structure qualifies; CONFIRMATION is what current/future price action provides.
 *
 * <p>Watch level: the triangle's apex / E-end resolves into a thrust direction. Until D is
 * observed, the watch is "next 3-wave leg in the alternating direction."
 * Invalidation: any observed leg of impulsive 5-wave character contradicts the all-corrective
 * signature.
 */
@Component
public final class TriangleSignatureRule implements EwSignatureRule {

    private static final Signature SIG = new Signature(
            "triangle",
            List.of("A", "B", "C", "D", "E"),
            List.of(LegCharacter.THREE, LegCharacter.THREE, LegCharacter.THREE,
                    LegCharacter.THREE, LegCharacter.THREE));

    @Override public String id() { return "triangle-3-3-3-3-3"; }
    @Override public String formName() { return "triangle"; }
    @Override public Signature signature() { return SIG; }

    @Override
    public AdmissionResult evaluate(List<ObservedLeg> observed) {
        return SignatureMatcher.match(SIG, observed);
    }

    @Override
    public DerivedLevels deriveLevels(List<ObservedLeg> observed, SymbolContext ctx) {
        // Triangle's level-map is highly state-dependent (which legs are formed). Conservative
        // PHASE-A version: invalidation = any wave-A pivot reversal that would deny the
        // contracting structure; watch = the next alternating swing target estimated from the
        // last observed leg.
        ObservedLeg lastObserved = null;
        for (ObservedLeg leg : observed) {
            if (leg.endPrice() != null) lastObserved = leg;
            else break;
        }
        if (lastObserved == null) {
            return new DerivedLevels(List.of(), List.of());
        }
        // Conservative invalidation: the original A_start (would mean correction failed entirely).
        double aStart = observed.get(0).startPrice();
        List<PriceLevel> invalidation = List.of(
                new PriceLevel(aStart, "above/below A_start invalidates triangle",
                        "triangle is corrective; reclaiming macro origin contradicts the corrective frame"));
        // Watch: the last observed leg's end is a structural pivot; the next leg's completion
        // would alternate direction. Without contraction ratios we cannot give a precise target;
        // surface the last-leg-end as a "structural watch" anchor.
        List<PriceLevel> watch = List.of(
                new PriceLevel(lastObserved.endPrice(),
                        "next-leg alternation watch (from " + lastObserved.label() + "_end)",
                        "triangle's next leg alternates direction from last observed pivot — exact target needs contraction-ratio (PHASE B / geometry)"));
        return new DerivedLevels(watch, invalidation);
    }
}
