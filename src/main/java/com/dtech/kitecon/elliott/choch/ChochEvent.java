package com.dtech.kitecon.elliott.choch;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import java.time.Instant;

public record ChochEvent(
    int barIndex,
    Instant timestamp,
    double price,
    Direction direction,
    ZigZagPoint sourcePivot,
    int originalChochPivotIdx,  // For state machine logic: the ChoCH pivot's index (vs barIndex which is anchored pivot's index)
    double originalChochPrice  // The original ChoCH event's price (for wave measurements)
) {}
