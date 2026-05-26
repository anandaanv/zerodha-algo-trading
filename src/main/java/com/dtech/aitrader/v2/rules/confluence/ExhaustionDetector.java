package com.dtech.aitrader.v2.rules.confluence;

import com.dtech.aitrader.v2.rules.IndicatorAccessor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic detectors for momentum exhaustion (owner memo {@code 34418c54}, Q2 position).
 *
 * <ul>
 *   <li>RSI divergence: price made a higher high while RSI made a lower high (bearish), or
 *       price made a lower low while RSI made a higher low (bullish).</li>
 *   <li>MACD-histogram contraction: |hist(now)| &lt; |hist(now-5)| — loss of thrust independent of
 *       direction. The detector sets {@code direction} to the side that opposes the hypothesis
 *       (LONG hypothesis exhausting up ⇒ BEARISH signal; SHORT hypothesis exhausting down ⇒
 *       BULLISH signal).</li>
 * </ul>
 *
 * <p>PHASE-A: detectors run on the same {@link IndicatorAccessor} as the structure TF (no separate
 * LTF accessor is wired into {@code SymbolContext} yet). Evidence is stamped
 * {@code tf_resolution=STRUCTURE_TF_ONLY_PHASE_A} so the audit trail shows where the LTF gap is.
 */
public final class ExhaustionDetector {

    public static final int MACD_LOOKBACK_BARS = 5;

    private ExhaustionDetector() { }

    public static Optional<ExhaustionSignal> detectRsiDivergence(
            IndicatorAccessor accessor,
            int firstPivotIdx,
            int secondPivotIdx,
            boolean priceMovedUp,
            String tfLabel) {
        if (accessor == null) return Optional.empty();
        if (firstPivotIdx < 0 || secondPivotIdx <= firstPivotIdx) return Optional.empty();
        int endIdx = accessor.series().getEndIndex();
        if (secondPivotIdx > endIdx) return Optional.empty();

        double firstClose = accessor.series().getBar(firstPivotIdx).getClosePrice().doubleValue();
        double secondClose = accessor.series().getBar(secondPivotIdx).getClosePrice().doubleValue();
        double firstRsi = accessor.rsi(firstPivotIdx);
        double secondRsi = accessor.rsi(secondPivotIdx);

        boolean divergence;
        String direction;
        if (priceMovedUp) {
            // price HH (or equal-and-higher), RSI LH ⇒ bearish divergence
            divergence = (secondClose > firstClose) && (secondRsi < firstRsi);
            direction = "BEARISH";
        } else {
            // price LL, RSI HL ⇒ bullish divergence
            divergence = (secondClose < firstClose) && (secondRsi > firstRsi);
            direction = "BULLISH";
        }
        if (!divergence) return Optional.empty();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("first_idx", firstPivotIdx);
        evidence.put("second_idx", secondPivotIdx);
        evidence.put("first_close", firstClose);
        evidence.put("second_close", secondClose);
        evidence.put("first_rsi", firstRsi);
        evidence.put("second_rsi", secondRsi);
        evidence.put("price_moved_up", priceMovedUp);
        evidence.put("tf_resolution", "STRUCTURE_TF_ONLY_PHASE_A");
        return Optional.of(new ExhaustionSignal("RSI_DIVERGENCE", tfLabel, direction, evidence));
    }

    public static Optional<ExhaustionSignal> detectMacdHistogramContraction(
            IndicatorAccessor accessor,
            int currentIdx,
            String tfLabel,
            String hypothesisDirection) {
        if (accessor == null) return Optional.empty();
        if (currentIdx < MACD_LOOKBACK_BARS) return Optional.empty();
        int endIdx = accessor.series().getEndIndex();
        if (currentIdx > endIdx) return Optional.empty();

        double histNow = accessor.macdHistogram(currentIdx);
        double histPrev = accessor.macdHistogram(currentIdx - MACD_LOOKBACK_BARS);
        if (Math.abs(histNow) >= Math.abs(histPrev)) return Optional.empty();

        // Hypothesis direction LONG ⇒ continuation up tiring ⇒ BEARISH signal (tilts reversal).
        // Hypothesis direction SHORT ⇒ continuation down tiring ⇒ BULLISH signal.
        String signalDirection = "LONG".equalsIgnoreCase(hypothesisDirection) ? "BEARISH"
                : "SHORT".equalsIgnoreCase(hypothesisDirection) ? "BULLISH"
                : "NEUTRAL";

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("current_idx", currentIdx);
        evidence.put("lookback_idx", currentIdx - MACD_LOOKBACK_BARS);
        evidence.put("hist_now", histNow);
        evidence.put("hist_prev", histPrev);
        evidence.put("abs_hist_now", Math.abs(histNow));
        evidence.put("abs_hist_prev", Math.abs(histPrev));
        evidence.put("hypothesis_direction", hypothesisDirection);
        evidence.put("tf_resolution", "STRUCTURE_TF_ONLY_PHASE_A");
        return Optional.of(new ExhaustionSignal("MACD_HISTOGRAM_CONTRACTION", tfLabel,
                signalDirection, evidence));
    }
}
