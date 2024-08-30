package com.dtech.kitecon.controller;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.runner.candle.LatestBarSeriesProvider;
import com.dtech.algo.series.Exchange;
import com.dtech.algo.series.InstrumentType;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.config.HistoricalDateLimit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class BarSeriesHelper {
    private final HistoricalDateLimit historicalDateLimit;
    private final LatestBarSeriesProvider barSeriesLoader;

    BarSeriesConfig createBarSeriesConfig(String symbol, String timeframe) {
        Interval interval = Interval.valueOf(timeframe); // Assuming you have an Interval enum
        int duration = historicalDateLimit.getScreenerDuration("NSE", interval);

        return BarSeriesConfig.builder()
                .interval(interval)
                .exchange(Exchange.NSE)
                .instrument(symbol)
                .instrumentType(InstrumentType.EQ)
                .startDate(LocalDate.now().minusDays(duration))
                .endDate(LocalDate.now())
                .name(symbol + "_" + timeframe)
                .build();
    }

    IntervalBarSeries getIntervalBarSeries(String stock, String tf) {
        BarSeriesConfig config = createBarSeriesConfig(stock, tf);
        IntervalBarSeries barSeries = null;
        try {
            barSeries = barSeriesLoader.loadBarSeries(config);
        } catch (StrategyException e) {
            throw new RuntimeException(e);
        }
        return barSeries;
    }
}
