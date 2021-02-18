package com.dtech.algo.strategy.sync;

import com.dtech.algo.series.Interval;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.ta4j.core.BaseBar;

@RequiredArgsConstructor
@Getter
public class CandleSyncToken {
    private final BaseBar baseBar;
    private final String instrument;
    private final Interval interval;
}
