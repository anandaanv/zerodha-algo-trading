package com.dtech.kitecon.controller;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.runner.candle.UpdatableBarSeriesLoader;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.config.HistoricalDateLimit;
import com.dtech.kitecon.repository.IndexSymbolRepository;
import com.dtech.kitecon.service.DataFetchService;
import com.dtech.ta.BarTuple;
import com.dtech.ta.TrendLineCalculated;
import com.dtech.ta.divergences.Divergence;
import com.dtech.ta.divergences.DivergenceAnalyzer;
import com.dtech.ta.elliott.AdvancedElliottWaveAnalyzer;
import com.dtech.ta.trendline.TrendlineAnalyser;
import com.dtech.ta.trendline.TrendlineTAConfirmation;
import com.dtech.ta.visualize.TrendlineVisualizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Slf4j
public class TrendlineController {

    private final TrendlineAnalyser trendlineAnalyser;
    private final IndexSymbolRepository indexSymbolRepository;
    private final UpdatableBarSeriesLoader barSeriesLoader;
    private final HistoricalDateLimit historicalDateLimit;
    private final DataFetchService dataFetchService;
    private final DivergenceAnalyzer divergenceAnalyzer;
    private final BarSeriesHelper barSeriesHelper;

    @GetMapping("/analyze/{symbol}/{timeframe}")
    @ResponseBody
    public List<String> analyzeTrendlines(@PathVariable String symbol, @PathVariable(required = false) String timeframe) {
        List<String> activeTrendlineStocks = new ArrayList<>();

        // Check if the symbol is an index
        if (indexSymbolRepository.existsByIndexName(symbol)) {
            List<String> stocks = indexSymbolRepository.findAllSymbolsByIndexName(symbol);
            for (String stock : stocks) {
                List<String> timeframes = timeframe != null ? Collections.singletonList(timeframe) : getAllTimeframes(); // Implement a method to get all available timeframes
                for (String tf : timeframes) {
                    analyseStock(stock, tf, activeTrendlineStocks);
                }
            }
        } else {
            // Analyze for a specific stock with or without timeframe
            if (timeframe == null) {
                List<String> timeframes = getAllTimeframes(); // Implement a method to get all available timeframes
                for (String tf : timeframes) {
                    analyseStock(symbol, tf, activeTrendlineStocks);

                }
            } else {
                analyseStock(symbol, timeframe, activeTrendlineStocks);
            }
        }

        return activeTrendlineStocks;
    }

    private void analyseStock(String stock, String tf, List<String> activeTrendlineStocks) {
        IntervalBarSeries barSeries = barSeriesHelper.getIntervalBarSeries(stock, tf);
//        if(barSeries.getEndIndex() < 100) {
//            log.warn("Very few available for " + stock + "_" + tf);
//            dataFetchService.updateInstrumentToLatest(stock, Interval.valueOf(tf), new String[]{"NSE"});
//            return;
//        }
        dataFetchService.updateInstrumentToLatest(stock, Interval.valueOf(tf), new String[]{"NSE"});
        log.info("analyse " + stock + "_" + tf);
        List<Divergence> divergences = divergenceAnalyzer.detectTripleDivergences(barSeries);
        if(!divergences.isEmpty() && new TrendlineTAConfirmation().validate(barSeries, barSeries.getEndIndex(), false)) {
//            log.info("STart processing for " + stock + "_" + tf);
            List<TrendLineCalculated> trendlines = trendlineAnalyser.analyze(barSeries, true);
            List<BarTuple> highLows = trendlineAnalyser.getCombinedHighLows(barSeries);
            String title = barSeries.getInstrument() + "_" + barSeries.getInterval().name();
            TrendlineVisualizer visualizer = new TrendlineVisualizer(title, barSeries, trendlines, highLows, divergences);
            visualizer.saveChartAsJPEG(title);
            activeTrendlineStocks.add(title);
        }
    }

    private List<String> getAllTimeframes() {
        return Arrays.stream(Interval.values()).map(Interval::name).collect(Collectors.toList());
    }


    @GetMapping("/elliott/{symbol}/{timeframe}")
    @ResponseBody
    public List<Integer> identiftElliottWave(@PathVariable String symbol, @PathVariable String timeframe) {
        BarSeriesConfig config = barSeriesHelper.createBarSeriesConfig(symbol, timeframe);
        BarSeriesConfig parentConfig = barSeriesHelper.createBarSeriesConfig(symbol, Interval.valueOf(timeframe).getParent().name());
        IntervalBarSeries mainBarSeries, parentBarSeries = null;
        try {
            mainBarSeries = barSeriesLoader.loadBarSeries(config);
            parentBarSeries = barSeriesLoader.loadBarSeries(parentConfig);
        } catch (StrategyException e) {
            throw new RuntimeException(e);
        }

        AdvancedElliottWaveAnalyzer elliottWaveAnalyzer = new AdvancedElliottWaveAnalyzer(mainBarSeries, parentBarSeries);
        return elliottWaveAnalyzer.detectWavesMultiTimeframe();
    }
}
