package com.dtech.aitrader.v2.narrative.stochrsi;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * StochRSI(14,14,3,3) parameters. Tunables tuned aggressively per delta 2fde845f Section 7
 * (FAILURE_004): StochRSI fires 2-4× more than RSI; expected post-filter beat count is LESS
 * than RSI on the same data. The filter has to bite.
 *
 * <p>Tighter knobs vs Stochastic:
 * <ul>
 *   <li>pivot atrMult higher (3.5 vs 2.5) — require bigger swings to register a peak/trough</li>
 *   <li>zoneMinPersistenceBars higher (3 vs 2) — drop short OB/OS pokes</li>
 *   <li>kdCrossMinPersistenceBars higher (5 vs 3) — kill more cross noise</li>
 *   <li>failedAttemptMinBars higher (5 vs 4) on midline crosses</li>
 *   <li>recentWindowBars shorter (36 vs 50) — shortest horizon of the set</li>
 *   <li>Cross significance threshold pushed to 0.85 (vs Stoch's 0.7) so even in-zone crosses
 *       only emit when persistence is solid (set via the engine's CROSSED gate)</li>
 * </ul>
 */
@Value
@Builder
public class StochRsiNarrativeParams {
    int period;
    int kSmoothing;
    int dSmoothing;

    SignificanceParams pivotParams;

    int presentWindowBars;
    int recentWindowBars;
    int regimeChangePersistenceBars;

    double oversoldThreshold;
    double overboughtThreshold;

    int zoneMinPersistenceBars;
    int kdCrossMinPersistenceBars;

    public static StochRsiNarrativeParams ofDefaults() {
        return StochRsiNarrativeParams.builder()
                .period(14)
                .kSmoothing(3)
                .dSmoothing(3)
                // Tuned to actual Slow %D dynamics on weekly: avg-abs-step ~9.4 on the test data.
                // atrMult=1.5 → threshold ≈ 14 (1.5 × 9.4). Reversal of ~14 points on a 0-100
                // oscillator is a meaningful swing — filters out the second-derivative noise
                // while keeping real peaks/troughs. pctMin=0.03 contributes ~1.5 floor.
                .pivotParams(new SignificanceParams(
                        14, 1.5, 0.03, 0.7, 4, false, 1.5, 20))
                .presentWindowBars(15)        // shorter than Stoch
                .recentWindowBars(36)         // shortest horizon
                .regimeChangePersistenceBars(8)
                .oversoldThreshold(20.0)
                .overboughtThreshold(80.0)
                .zoneMinPersistenceBars(3)    // tighter than Stoch's 2
                .kdCrossMinPersistenceBars(5) // tighter than Stoch's 3
                .build();
    }
}
