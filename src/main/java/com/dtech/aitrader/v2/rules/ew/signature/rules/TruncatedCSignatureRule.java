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
 * Truncated-C zigzag: same signature as a standard zigzag (5-3-5) but C falls SHORT of the
 * C=A projection target. Admitted same way as zigzag; differs in the level-map — its C target
 * is conservative (less than B_end ± |A magnitude|), and the existence of a separate
 * truncated-C hypothesis means the correction may be near completion / weaker than nominal.
 *
 * <p>Per owner's reframe ({@code 159ba913}): truncated-C is one of the three live hypotheses
 * when low is unbroken — it expresses "correction weaker / near done."
 *
 * <p>Admission/invalidation: same as zigzag (matches 5-3-5). The DIFFERENCE is the level-map
 * contribution — truncated-C surfaces an EARLIER C-target level (e.g. ≈ A_end, or a Fib
 * retracement of B-leg) as the watch, plus the standard A_start invalidation.
 */
@Component
public final class TruncatedCSignatureRule implements EwSignatureRule {

    private static final Signature SIG = new Signature(
            "truncated-c",
            List.of("A", "B", "C"),
            List.of(LegCharacter.FIVE, LegCharacter.THREE, LegCharacter.FIVE));

    @Override public String id() { return "truncated-c-5-3-5"; }
    @Override public String formName() { return "truncated-c"; }
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
        // Truncated-C target: C falls SHORT of the C=A projection. Conservative options:
        //   (a) ≈ A_end (correction completes at the prior counter; "truncated to A_end")
        //   (b) midway between B_end and (B_end ± |A|) — partial-projection
        double cAtAEnd = aEnd;
        double cPartial = downside ? (bEnd - 0.618 * aMag) : (bEnd + 0.618 * aMag);
        List<PriceLevel> watch = List.of(
                new PriceLevel(cAtAEnd, "truncated-C target ≈ A_end",
                        "correction completes near prior counter; C fails to extend"),
                new PriceLevel(cPartial, "truncated-C target (0.618×A)",
                        "partial C-projection — C runs ~0.618×|A| from B_end"));
        List<PriceLevel> invalidation = List.of(
                new PriceLevel(aStart, "above A_start invalidates truncated-C",
                        "if price reclaims the macro origin, the corrective frame dies"));
        return new DerivedLevels(watch, invalidation);
    }
}
