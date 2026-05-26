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
 * Impulse signature: <code>W1=FIVE, W2=THREE, W3=FIVE, W4=THREE, W5=FIVE</code>. Standard
 * 5-3-5-3-5 impulse with alternating impulsive/corrective sub-waves.
 *
 * <p>Watch level: W3 target ≈ W1_end + 1.618×|W1 magnitude| (canonical extension).
 * Invalidation: a W2 retrace > 100% of W1 (price below W0) kills the impulse.
 */
@Component
public final class ImpulseSignatureRule implements EwSignatureRule {

    private static final Signature SIG = new Signature(
            "impulse",
            List.of("W1", "W2", "W3", "W4", "W5"),
            List.of(LegCharacter.FIVE, LegCharacter.THREE, LegCharacter.FIVE,
                    LegCharacter.THREE, LegCharacter.FIVE));

    @Override public String id() { return "impulse-5-3-5-3-5"; }
    @Override public String formName() { return "impulse"; }
    @Override public Signature signature() { return SIG; }

    @Override
    public AdmissionResult evaluate(List<ObservedLeg> observed) {
        return SignatureMatcher.match(SIG, observed);
    }

    @Override
    public DerivedLevels deriveLevels(List<ObservedLeg> observed, SymbolContext ctx) {
        ObservedLeg w1 = observed.get(0);
        if (w1.endPrice() == null) {
            return new DerivedLevels(List.of(), List.of());
        }
        double w0 = w1.startPrice();
        double w1End = w1.endPrice();
        double w1Mag = Math.abs(w1End - w0);
        boolean bullish = w1End > w0;
        double w3Target = bullish ? (w1End + 1.618 * w1Mag) : (w1End - 1.618 * w1Mag);
        List<PriceLevel> watch = List.of(
                new PriceLevel(w3Target, "W3 target (1.618×W1 extension)",
                        "W3 = W1_end ± 1.618×|W1| (W1_end " + w1End + ", |W1| " + w1Mag + ")"));
        List<PriceLevel> invalidation = List.of(
                new PriceLevel(w0, bullish ? "W2 below W0 invalidates impulse" : "W2 above W0 invalidates impulse",
                        "W2 retrace cannot exceed 100% of W1 (Rule 3)"));
        return new DerivedLevels(watch, invalidation);
    }
}
