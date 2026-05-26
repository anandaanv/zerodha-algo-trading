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
 * Flat signature: <code>A=THREE, B=THREE, C=FIVE</code>. A 3-3-5 corrective. Distinguished from
 * zigzag by A being corrective (3-wave) instead of impulsive.
 *
 * <p>Watch level: C target ≈ A_end (regular flat) or 1.272×|A| beyond A_end (expanded flat).
 * Invalidation: above A_start kills the corrective.
 */
@Component
public final class FlatSignatureRule implements EwSignatureRule {

    private static final Signature SIG = new Signature(
            "flat",
            List.of("A", "B", "C"),
            List.of(LegCharacter.THREE, LegCharacter.THREE, LegCharacter.FIVE));

    @Override public String id() { return "flat-3-3-5"; }
    @Override public String formName() { return "flat"; }
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
        boolean downside = aStart > aEnd;
        // Regular flat: C ≈ A_end. Expanded flat: C extends beyond A_end by 1.272×|A|.
        double cRegular = aEnd;
        double aMag = Math.abs(aEnd - aStart);
        double cExpanded = downside ? (bEnd - 1.272 * aMag) : (bEnd + 1.272 * aMag);
        List<PriceLevel> watch = List.of(
                new PriceLevel(cRegular, "C target (regular flat)", "C ≈ A_end (3-3-5)"),
                new PriceLevel(cExpanded, "C target (expanded flat)", "C = 1.272×|A| beyond B_end"));
        List<PriceLevel> invalidation = List.of(
                new PriceLevel(aStart, "above A_start invalidates flat",
                        "corrective dies if price reclaims the macro origin"));
        return new DerivedLevels(watch, invalidation);
    }
}
