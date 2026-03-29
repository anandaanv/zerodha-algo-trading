package com.dtech.ta.elliott.trigger;

import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandleTriggerDetectorTest {

    private CandleTriggerDetector detector;

    @BeforeEach
    void setUp() {
        detector = new CandleTriggerDetector();
    }

    private Bar bar(double open, double high, double low, double close) {
        return BarsLoader.getBar(open, high, low, close, 1000, Instant.now());
    }

    private BarSeries seriesOfOne(double open, double high, double low, double close) {
        BarSeries series = new BaseBarSeriesBuilder().withName("test").build();
        series.addBar(bar(open, high, low, close));
        return series;
    }

    private BarSeries seriesOfTwo(double o1, double h1, double l1, double c1,
                                   double o2, double h2, double l2, double c2) {
        BarSeries series = new BaseBarSeriesBuilder().withName("test").build();
        series.addBar(BarsLoader.getBar(o1, h1, l1, c1, 1000, Instant.now().minusSeconds(86400)));
        series.addBar(BarsLoader.getBar(o2, h2, l2, c2, 1000, Instant.now()));
        return series;
    }

    @Test
    void detects_hammer() {
        // open=110, high=112, low=100, close=109
        // range=12, body=1(8.3%), lowerWick=9(75%), closeLocation=0.75
        BarSeries series = seriesOfOne(110, 112, 100, 109);
        List<CandleTrigger> result = detector.detect(series, 1);
        assertEquals(1, result.size());
        assertEquals(TriggerType.HAMMER, result.get(0).getType());
        assertTrue(result.get(0).isBullish());
    }

    @Test
    void detects_shooting_star() {
        // open=100, high=112, low=99, close=101
        // range=13, body=1(7.7%), upperWick=11(84.6%), closeLocation=0.15
        BarSeries series = seriesOfOne(100, 112, 99, 101);
        List<CandleTrigger> result = detector.detect(series, 1);
        assertEquals(1, result.size());
        assertEquals(TriggerType.SHOOTING_STAR, result.get(0).getType());
        assertFalse(result.get(0).isBullish());
    }

    @Test
    void no_trigger_on_normal_bar() {
        // Normal bar with roughly equal body and wicks
        BarSeries series = seriesOfOne(100, 105, 98, 103);
        List<CandleTrigger> result = detector.detect(series, 1);
        assertTrue(result.stream().noneMatch(t ->
                t.getType() == TriggerType.HAMMER || t.getType() == TriggerType.SHOOTING_STAR));
    }

    @Test
    void detects_bullish_engulfing() {
        // prev: down bar (open=105, close=100)
        // curr: up bar that engulfs prev body (open=98, close=106)
        BarSeries series = seriesOfTwo(105, 106, 99, 100,
                                        98, 107, 97, 106);
        List<CandleTrigger> result = detector.detect(series, 2);
        assertTrue(result.stream().anyMatch(t -> t.getType() == TriggerType.BULLISH_ENGULFING));
        result.stream()
              .filter(t -> t.getType() == TriggerType.BULLISH_ENGULFING)
              .findFirst()
              .ifPresent(t -> assertTrue(t.isBullish()));
    }

    @Test
    void empty_lookback_returns_empty() {
        BarSeries series = seriesOfOne(100, 110, 90, 105);
        List<CandleTrigger> result = detector.detect(series, 0);
        assertTrue(result.isEmpty());
    }

    @Test
    void null_series_returns_empty() {
        List<CandleTrigger> result = detector.detect(null, 3);
        assertTrue(result.isEmpty());
    }
}
