package com.dtech.kitecon.patternscanner;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class PatternDto {
    String patternType;       // DOUBLE_BOTTOM, DOUBLE_TOP, TRIANGLE_ASCENDING, etc.
    boolean bullish;
    double keyLevel;          // neckline / breakout level
    Instant keyLevelTime;
    double target;            // measured-move target
    double patternHeight;
    double atr;
    double rsiAtP1;
    double rsiAtP2;
    double rrRatio;
    // anchor times for the pattern shape (for overlay rendering)
    Instant p0Time;           // first top/bottom
    Instant p1Time;           // neckline
    // p2Time = keyLevelTime (second top/bottom)

    // Watching TF indicators (at keyLevelTime)
    double macdHistAtP1;
    double macdHistAtP2;
    double stochRsiK;
    double adxWatching;
    double adxWatchingEma;
    double macdWatching;
    double macdSignalWatching;
    double bbWidthWatching;
    double bbPctBWatching;
    // Confirm TF indicators (at keyLevelTime)
    double adxConfirm;
    double adxConfirmEma;
    // Daily TF indicators (at keyLevelTime)
    double dailyRsi;
    double dailyAdx;
    double dailyAdxEma;
    double macdDaily;
    double macdSignalDaily;
    double bbWidthDaily;
    double bbPctBDaily;
    // Derived slope/expansion features
    double bbExpanding;      // 1.0 if BB width expanding at entry vs 5 bars ago
    double bbAligned;        // 1.0 if expanding and pct_b aligns with trade direction
    double rsiSlope;         // linear regression slope of RSI over last 5 bars (watching TF)
    double macdHistSlope;    // slope of MACD histogram over last 5 bars (watching TF)
    double adxSlope;         // slope of ADX over last 5 bars (watching TF)

    // Pivot fields for DTB+HNS candidate-based entry-confirmation flow
    double pivotP0;          // Latest pivot OR trailing-extreme candidate
    double pivotP1;          // Previous pivot
    double pivotP2;          // Previous-previous pivot
    Double pivotP3;          // Previous-previous-previous (HNS only, nullable)
    double breakoutLevel;    // Entry confirmation level (populated in Phase 2)
}
