package com.dtech.aitrader.v2.narrative.keltner;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Keltner Channels(20, 2×ATR). REGIME_EPISODE tier per Narrative Core 533b3e85. EQUIV_CLASS_005
 * (Volatility-Compression-Squeeze) with Bollinger — same observation (compression), different
 * computation (ATR-based vs stdev-based). Downstream de-dups them.
 */
@Value
@Builder
public class KeltnerSeries implements IndicatorSeries {
    double[] middle;     // EMA(period) of close
    double[] upper;      // middle + atrMult × ATR(atrPeriod)
    double[] lower;      // middle − atrMult × ATR(atrPeriod)
    double[] width;      // (upper − lower) / middle × 100
    double[] closes;
    double[] adx;        // for band-walk vs reversion disambiguation
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return middle.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        switch (component) {
            case KELTNER_MIDDLE: return middle;
            case KELTNER_UPPER: return upper;
            case KELTNER_LOWER: return lower;
            case KELTNER_WIDTH: return width;
            default:
                throw new IllegalArgumentException("KeltnerSeries has no component " + component);
        }
    }
}
