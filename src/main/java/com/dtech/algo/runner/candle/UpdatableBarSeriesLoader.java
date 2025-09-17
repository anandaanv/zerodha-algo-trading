package com.dtech.algo.runner.candle;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.data.Instrument;
import org.ta4j.core.Bar;

public interface UpdatableBarSeriesLoader extends BarSeriesLoader {
     /**
     * Updates a bar series with a new tick
     * 
     * @param tick The market data tick
     * @param barSeries The pre-loaded bar series to update
     * @return The completed (previous) bar if a new bar was created, null otherwise
     */
    Bar updateBarSeries(DataTick tick, IntervalBarSeries barSeries);

}
