package com.dtech.chartpattern.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Configuration
@PropertySource(value = "classpath:chartpattern-defaults.yml", factory = com.dtech.chartpattern.config.YamlPropertySourceFactory.class)
@ConfigurationProperties(prefix = "chartpattern")
@Data
public class ChartPatternProperties {

    private ZigZagDefaults zigzag = new ZigZagDefaults();
    private TrendlineDefaults trendline = new TrendlineDefaults();
    private Map<String, List<String>> indexes = Collections.emptyMap();

    @Data
    public static class ZigZagDefaults {
        private int atrLength = 14;
        private double atrMult = 2.0;
        private double pctMin = 0.03;
        private double hysteresis = 1.6;
        private int minBarsBetweenPivots = 3;
        private String mode = "LIVE"; // LIVE or BACKTEST
        private boolean dynamicPctEnabled = true;
        private double volMult = 2.0;
        private int rvolWindow = 50;
    }

    @Data
    public static class TrendlineDefaults {
        private int recentPivotCount = 60;
        private int minGapBars = 10;
        private double pctBand = 0.003;
        private double atrBandMult = 0.4;
        private int maxLinesPerSide = 3;
        private double flatAngleDeg = 2.0;
        private int containmentWindowBars = 250;
        private double maxProximityNowPct = 0.01;
        private int recentEmphasisBars = 600;
        private double recencyWeight = 0.3;
        private double coverageMin = 0.55;
        private double breachMax = 0.25;
        private int ransacSamples = 600;
        private double slopeSimilarityEps = 0.00005;
        private double emaProximityPct = 0.004;
        private double bbStdDev = 2.0;
        private boolean allowTwoLatestPivots = true;
    }
}
