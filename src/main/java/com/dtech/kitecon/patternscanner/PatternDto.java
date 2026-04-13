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
}
