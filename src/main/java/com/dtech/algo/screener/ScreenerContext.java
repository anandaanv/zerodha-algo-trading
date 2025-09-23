package com.dtech.algo.screener;

import com.dtech.algo.screener.domain.Screener;
import com.dtech.algo.series.IntervalBarSeries;
import lombok.Builder;
import lombok.Value;
import org.ta4j.core.BarSeries;

import java.util.Map;

@Value
@Builder(toBuilder = true)
public class ScreenerContext {
    // Alias -> BarSeries (e.g., "base", "wave", "index")
    Map<String, IntervalBarSeries> aliases;

    // Arbitrary parameters (e.g., fast=9, slow=21)
    Map<String, Object> params;

    // The index at which to evaluate (usually series.endIndex)
    int nowIndex;

    // Optional metadata for reporting
    String symbol;
    String timeframe;

    // Business Screener model (parsed from entity)
    Screener screener;

    public BarSeries getSeries(String alias) {
        return aliases != null ? aliases.get(alias) : null;
    }

    public Object getParam(String key) {
        return params != null ? params.get(key) : null;
    }
}
