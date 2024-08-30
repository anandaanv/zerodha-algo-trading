package com.dtech.ta.trendline;

import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.ta.visualize.TrendlineVisualizer;
import com.dtech.ta.BarTuple;
import com.dtech.ta.TrendLineCalculated;
import com.dtech.ta.patterns.TriangleVisualizationTest;
import org.jfree.chart.ui.UIUtils;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

class TrendLineDetectionTest {


    @Test
    void detectTrendlines() {
//        BarSeries series = PatternTestHelper.createBarSeriesFromCSV("test_data/NSE_ROSSARI_1D.csv");
        IntervalBarSeries series = TriangleVisualizationTest.createBarSeriesFromCSV("test_data/rossari_60.csv");

        ActiveTrendlineAnalysis detection = new ActiveTrendlineAnalysis();
        // Detect trendlines for support
        List<TrendLineCalculated> supportTrendlines = detection.analyze(series, false);
        List<BarTuple> maxima  = detection.getCombinedHighLows(series);
//        List<BarTuple> minima  = detection.getCombinedHighLows(series);

//        List<TrendLineCalculated> resistanceTrendlines = detection.detectTrendlines(TrendlineType.RESISTANCE, false);

        for (TrendLineCalculated trendline : supportTrendlines) {
            System.out.println(trendline);
        }
//        for (TrendLineCalculated trendline : resistanceTrendlines) {
//            System.out.println(trendline);
//        }

//        List<TrendLineCalculated> allTrendLines = supportTrendlines.stream().collect(Collectors.toList());
//        allTrendLines.addAll(resistanceTrendlines);

        TrendlineVisualizer chart = new TrendlineVisualizer("TrendLines", series, supportTrendlines, maxima, Collections.emptyList());
//        chart.pack();
//        UIUtils.centerFrameOnScreen(chart);
//        chart.setVisible(true);
        chart.saveChartAsJPEG("Trendlines");

    }
}