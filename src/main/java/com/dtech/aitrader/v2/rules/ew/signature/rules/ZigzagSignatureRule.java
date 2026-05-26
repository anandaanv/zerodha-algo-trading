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
 * Zigzag signature: <code>A=FIVE, B=THREE, C=FIVE</code>. A standard 5-3-5 corrective.
 *
 * <p>Watch level: C target = B_end - |A magnitude| (downside) or B_end + |A magnitude| (upside).
 * Invalidation: above A_start (downside) / below A_start (upside) kills the corrective.
 */
@Component
public final class ZigzagSignatureRule implements EwSignatureRule {

    private static final Signature SIG = new Signature(
            "zigzag",
            List.of("A", "B", "C"),
            List.of(LegCharacter.FIVE, LegCharacter.THREE, LegCharacter.FIVE));

    @Override public String id() { return "zigzag-5-3-5"; }
    @Override public String formName() { return "zigzag"; }
    @Override public Signature signature() { return SIG; }

    @Override
    public AdmissionResult evaluate(List<ObservedLeg> observed) {
        return SignatureMatcher.match(SIG, observed);
    }

    @Override
    public DerivedLevels deriveLevels(List<ObservedLeg> observed, SymbolContext ctx) {
        ObservedLeg a = observed.get(0);
        ObservedLeg b = observed.get(1);
        if (a.endPrice() == null || b.endPrice() == null) {
            return new DerivedLevels(List.of(), List.of());
        }
        double aStart = a.startPrice();
        double aEnd = a.endPrice();
        double bEnd = b.endPrice();
        double aMag = Math.abs(aEnd - aStart);
        boolean downside = aStart > aEnd;
        double cTarget = downside ? (bEnd - aMag) : (bEnd + aMag);
        List<PriceLevel> watch = List.of(
                new PriceLevel(cTarget, "C target (zigzag)",
                        "C = A projection: B_end ± |A magnitude| (B_end " + bEnd + ", |A| " + aMag + ")"));
        List<PriceLevel> invalidation = List.of(
                new PriceLevel(aStart, "above A_start invalidates zigzag",
                        "corrective zigzag dies if price reclaims the macro origin"));
        return new DerivedLevels(watch, invalidation);
    }
}
