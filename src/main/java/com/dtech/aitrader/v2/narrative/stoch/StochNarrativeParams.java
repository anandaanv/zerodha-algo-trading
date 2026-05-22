package com.dtech.aitrader.v2.narrative.stoch;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * Stochastic narrative parameters. Slow Stochastic (14,3,3) defaults per Lane and the delta spec
 * (memsys 5e359982). SHORT horizon — everything fast-decay, no regime verb. Mirror of MACD/RSI
 * config but with very different retention.
 */
@Value
@Builder
public class StochNarrativeParams {
    int kPeriod;          // raw %K lookback (default 14)
    int kSmoothing;       // smoothing for Slow %K (default 3)
    int dSmoothing;       // smoothing for Slow %D (default 3)

    SignificanceParams pivotParams;

    int presentWindowBars;
    int recentWindowBars;
    int regimeChangePersistenceBars;

    /** OS upper bound (default 20 per Lane). */
    double oversoldThreshold;

    /** OB lower bound (default 80 per Lane). */
    double overboughtThreshold;

    /** Minimum bars an OB/OS visit must last to register (filters one-bar pokes). */
    int zoneMinPersistenceBars;

    /**
     * Minimum bars Stoch %K must stay above/below %D for a crossover to count. The delta says
     * "%K/%D crosses far more often than MACD" — strong filter needed. ~3 weekly bars is a sane
     * floor (kills 1-2 bar wobble).
     */
    int kdCrossMinPersistenceBars;

    public static StochNarrativeParams ofDefaults() {
        return StochNarrativeParams.builder()
                .kPeriod(14)
                .kSmoothing(3)
                .dSmoothing(3)
                .pivotParams(new SignificanceParams(
                        14, 2.5, 0.02, 0.7, 3, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(50)        // SHORT horizon per delta
                .regimeChangePersistenceBars(8)
                .oversoldThreshold(20.0)
                .overboughtThreshold(80.0)
                .zoneMinPersistenceBars(2)
                .kdCrossMinPersistenceBars(3)
                .build();
    }
}
