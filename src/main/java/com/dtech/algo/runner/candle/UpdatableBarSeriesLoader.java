package com.dtech.algo.runner.candle;

import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;

public interface UpdatableBarSeriesLoader extends BarSeriesLoader {
    void updateBarSeries(DataTick tick);
}
