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
    // anchor times for the pattern shape (for overlay rendering)
    Instant p0Time;           // first top/bottom
    Instant p1Time;           // neckline
    // p2Time = keyLevelTime (second top/bottom)
}
