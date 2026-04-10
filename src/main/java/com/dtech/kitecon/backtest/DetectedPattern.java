package com.dtech.kitecon.backtest;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

/**
 * Lightweight result from a single-TF pattern scan.
 * Used as both "watching pattern" (higher TF) and "confirmation pattern" (lower TF)
 * in the multi-TF combo backtest.
 */
@Data
@Builder
public class DetectedPattern {
    String  patternType;       // DOUBLE_BOTTOM, DOUBLE_TOP, TRIANGLE_ASCENDING, etc.
    String  confirmationType;  // C1, C3, CANDLE_CLUSTER, etc.
    boolean bullish;

    // For watching patterns: the price zone to watch on lower TF
    double  keyLevel;
    Instant keyLevelTime;

    // For confirmation patterns (and combo entry): actual trade fields
    double  entryPrice;
    double  stopLoss;
    double  ownTarget;         // this pattern's own projected target (not combo target)
    double  patternHeight;
    double  atr;               // ATR at keyLevel/entry bar

    // Indicator snapshots for CSV
    double  rsiAtP1;
    double  rsiAtP2;
    double  macdHistAtP1;
    double  macdHistAtP2;
    double  stochRsiK;
    double  dailyRsi;
    double  macdHistogram;
    String  reversalPattern;
}
