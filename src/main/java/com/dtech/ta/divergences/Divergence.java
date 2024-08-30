package com.dtech.ta.divergences;

import com.dtech.ta.BarTuple;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Data
public class Divergence {
    private final DivergenceType type;
    private final IndicatorType indicator;
    private final DivergenceDirection direction;
    private final List<BarTuple> candles = new ArrayList<>();

    // Method to add candles
    public void addCandle(BarTuple candle) {
        this.candles.add(candle);
    }
}
