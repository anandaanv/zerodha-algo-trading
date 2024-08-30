package com.dtech.kitecon.controller;

import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.ta.BarTuple;
import com.dtech.ta.TrendLineCalculated;
import com.dtech.ta.elliott.RefinedLocalExtremesDetector;
import com.dtech.ta.elliott.Wave;
import com.dtech.ta.elliott.priceaction.PriceAction;
import com.dtech.ta.elliott.priceaction.PriceActionAnalyzer;
import com.dtech.ta.elliott.wave.Wave1Detector;
import com.dtech.ta.visualize.TrendlineVisualizer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class ElliottVisualizerController {

    private final BarSeriesHelper barSeriesHelper;

    // Endpoint to generate and return the visualization as a JPG
    @GetMapping(value = "/price-action/{stock}/{tf}")
    public String visualizePriceAction(@PathVariable String stock, @PathVariable String tf) throws IOException {
        // Dummy BarTuple and PriceAction data (replace with real data)
        IntervalBarSeries barSeries = barSeriesHelper.getIntervalBarSeries(stock, tf);
        RefinedLocalExtremesDetector refinedLocalExtremesDetector = new RefinedLocalExtremesDetector(barSeries, 20, 5);
        List<BarTuple> extremes = refinedLocalExtremesDetector.detectLocalExtremes();
        List<PriceAction> priceActions = new PriceActionAnalyzer(barSeries).analyzePriceActions(extremes);
        List<TrendLineCalculated> trendLines = priceActions.stream().map(p -> {
            return new TrendLineCalculated(barSeries, 0.0, 0.0,
                    Arrays.asList(p.getStart(), p.getEnd()), true);
        }).toList();

        // File path to save the JPG
        String filePath = stock + "_" + tf;
        Wave1Detector detector = new Wave1Detector(barSeries, priceActions);
        // Generate and save the chart as JPG
        List<Wave> wave1 = detector.detectPotentialWave1s()
                .stream().map(pa -> new Wave(1, pa)).collect(Collectors.toList());
        TrendlineVisualizer tlvisualizer = new TrendlineVisualizer(filePath, barSeries, trendLines, extremes, Collections.emptyList(), false, wave1);
        tlvisualizer.saveChartAsJpg( filePath, "elliott/");


        // Return the image as a response
        return filePath;
    }
}
