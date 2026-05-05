package com.dtech.ta.patterns;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.backtest.DetectedPattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature extractor for DTB+HNS patterns.
 * Extracted from DtbHnsTrainingDataService for reuse in live and simulation contexts.
 */
@Slf4j
@Component
public class DtbHnsFeatureExtractor {

    /**
     * Extract ~400 features from the pattern and surrounding bars.
     * Mirrors ImpulseFeatureExtractor approach with DTB/HNS-specific features.
     */
    public double[] extract(BarSeries series, DetectedPattern pattern,
                            List<ZigZagPoint> pivots, int detectionBarIdx,
                            double[] atrArr, double[] rsiValues,
                            double[] macdHistArr, double[] stochRsiK) {
        List<Double> features = new ArrayList<>();

        // Pattern metadata
        features.add(pattern.isBullish() ? 1.0 : 0.0);  // direction
        features.add("DOUBLE_BOTTOM".equals(pattern.getPatternType()) ? 1.0 :
                "DOUBLE_TOP".equals(pattern.getPatternType()) ? 2.0 : 0.0);  // pattern type encoding
        features.add(pattern.getPatternHeight());
        features.add(pattern.getAtr());

        // Pivot prices (P0, P1, P2, P3)
        features.add(pattern.getPivotP0());
        features.add(pattern.getPivotP1());
        features.add(pattern.getPivotP2());
        if (pattern.getPivotP3() != null) features.add(pattern.getPivotP3()); else features.add(0.0);

        // Indicator values at pivots
        features.add(pattern.getRsiAtP0());
        features.add(pattern.getRsiAtP1());
        features.add(pattern.getRsiAtP2());
        features.add(pattern.getMacdHistAtP1());
        features.add(pattern.getMacdHistAtP2());
        features.add(pattern.getStochRsiK());

        // Pivot ratios and distances
        double p0 = pattern.getPivotP0();
        double p1 = pattern.getPivotP1();
        double p2 = pattern.getPivotP2();

        // Retrace ratio P0 from P1-P2 leg
        double leg = Math.abs(p1 - p2);
        if (leg > 0) {
            double retrace = Math.abs(p1 - p0) / leg;
            features.add(retrace);
        } else {
            features.add(0.0);
        }

        // Pattern height as % of baseline
        if (p0 > 0) features.add(pattern.getPatternHeight() / p0 * 100.0); else features.add(0.0);

        // ATR ratios
        if (pattern.getAtr() > 0) {
            features.add(pattern.getPatternHeight() / pattern.getAtr());  // height/ATR
        } else {
            features.add(0.0);
        }

        // Current bar features
        if (detectionBarIdx >= 0 && detectionBarIdx < series.getBarCount()) {
            Bar bar = series.getBar(detectionBarIdx);
            double barHigh = bar.getHighPrice().doubleValue();
            double barLow = bar.getLowPrice().doubleValue();
            double barClose = bar.getClosePrice().doubleValue();
            double barOpen = bar.getOpenPrice().doubleValue();

            if (barClose > 0) {
                features.add((barHigh - barClose) / barClose * 100.0);  // upper wick %
                features.add((barClose - barLow) / barClose * 100.0);   // lower wick %
                features.add(Math.abs(barClose - barOpen) / barClose * 100.0);  // body %
            } else {
                features.add(0.0);
                features.add(0.0);
                features.add(0.0);
            }
        } else {
            features.add(0.0);
            features.add(0.0);
            features.add(0.0);
        }

        // Price momentum features from surrounding bars
        for (int offset = -5; offset <= 5; offset++) {
            int idx = detectionBarIdx + offset;
            if (idx >= 0 && idx < series.getBarCount() && idx < rsiValues.length) {
                features.add(rsiValues[idx]);
            } else {
                features.add(50.0);  // neutral RSI
            }
        }

        // Pattern key level
        features.add(pattern.getKeyLevel());
        if (pattern.getKeyLevel() > 0 && detectionBarIdx >= 0 && detectionBarIdx < series.getBarCount()) {
            Bar detBar = series.getBar(detectionBarIdx);
            features.add((detBar.getClosePrice().doubleValue() - pattern.getKeyLevel()) / pattern.getKeyLevel() * 100.0);
        } else {
            features.add(0.0);
        }

        // Daily RSI (using keyLevelTime if available)
        features.add(pattern.getDailyRsi());

        // Pad to ~400 features
        while (features.size() < 400) {
            features.add(0.0);
        }

        // Convert to double array
        double[] result = new double[Math.min(features.size(), 400)];
        for (int i = 0; i < result.length; i++) {
            Double val = features.get(i);
            result[i] = val != null ? val : 0.0;
            if (Double.isNaN(result[i]) || Double.isInfinite(result[i])) {
                result[i] = 0.0;
            }
        }
        return result;
    }
}
