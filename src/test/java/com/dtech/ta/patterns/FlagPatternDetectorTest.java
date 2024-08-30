package com.dtech.ta.patterns;

import com.dtech.chart.FlagVisualizer;
import org.jfree.chart.ui.UIUtils;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.util.List;

class FlagPatternDetectorTest {

    @Test
    void detectFlags() {
        BarSeries series = PatternTestHelper.createBarSeriesFromCSV("test_data/rossari_60.csv");
        FlagPatternDetector detector = new FlagPatternDetector(series, new IndicatorCalculator(series, 14));
        List<FlagPattern> flags = detector.detectFlags(10, 0.02);
        System.out.println(flags);
        FlagVisualizer chart = new FlagVisualizer("My Chart", series, flags);
        chart.pack();
        UIUtils.centerFrameOnScreen(chart);
        chart.setVisible(true);
        chart.saveChartAsJPEG("MyChartFlag");
    }
}