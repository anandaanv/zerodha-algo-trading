package com.dtech.algo.screener;

import lombok.Builder;
import lombok.Value;
import org.ta4j.core.BarSeries;

import java.util.Map;

@Value
@Builder
public class ScreenerContext {
    // Alias -> BarSeries (e.g., "base", "wave", "index")
    Map<String, BarSeries> aliases;

    // Arbitrary parameters (e.g., fast=9, slow=21)
    Map<String, Object> params;

    // The index at which to evaluate (usually series.endIndex)
    int nowIndex;

    // Optional metadata for reporting
    String symbol;
    String timeframe;

    public BarSeries getSeries(String alias) {
        return aliases != null ? aliases.get(alias) : null;
    }

    public Object getParam(String key) {
        return params != null ? params.get(key) : null;
    }
}
