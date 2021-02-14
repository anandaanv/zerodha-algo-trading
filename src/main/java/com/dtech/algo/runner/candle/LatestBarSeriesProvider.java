package com.dtech.algo.runner.candle;


import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;

/**
 * Expected to cache bar series from the request, The request will be similar to that comes for strategy.
 */
@RequiredArgsConstructor
@Component
public class LatestBarSeriesProvider implements UpdatableBarSeriesLoader {

    private final BarSeriesLoader delegate;

    @Cacheable(cacheNames = "barSeries")
    public IntervalBarSeries loadBarSeries(BarSeriesConfig barSeriesConfig) throws StrategyException {
        try {
            BarSeriesConfig refSeries = barSeriesConfig.clone();
            int diff = Period.between(barSeriesConfig.getStartDate(), barSeriesConfig.getEndDate()).getDays();
            refSeries.setStartDate(refSeries.getEndDate().minusDays(diff));
            refSeries.setEndDate(LocalDate.now());
            return delegate.loadBarSeries(refSeries);
        } catch (CloneNotSupportedException e) {
            throw new StrategyException(e);
        }
    }

}
