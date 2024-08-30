package com.dtech.ta.patterns;

import com.dtech.ta.TrendLineCalculated;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.util.List;

class DoubleBottomDetectorTest {

    @Test
    void detectDoubleBottoms() {
        BarSeries series = PatternTestHelper.createBarSeriesFromCSV("test_data/NSE_ROSSARI_1D.csv");

        DoubleBottomDetector detector = new DoubleBottomDetector(series);

        List<TrendLineCalculated> patterns = detector.detectDoubleBottoms(150);
        patterns.forEach(System.out::println);
    }
}