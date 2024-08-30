package com.dtech.ta;

import org.ta4j.core.BarSeries;

public class TrendlineReliability {
    public static double assessTrendlineReliability(TrendLineCalculated trendline, BarSeries series) {
        double reliabilityScore = 0.0;

        // Factor 1: Number of support/resistance interactions
        int supportResistanceCount = 0;
        int breakCount = 0;

        BarTuple firstPoint = trendline.getPoints().get(0);
        BarTuple lastPoint = trendline.getPoints().get(trendline.getPoints().size() - 1);

        for (int i = firstPoint.getIndex(); i <= lastPoint.getIndex(); i++) {
            double trendlinePrice = trendline.getSlope() * i + trendline.getIntercept();
            double closePrice = series.getBar(i).getClosePrice().doubleValue();

            if ((trendline.isSupport() && closePrice <= trendlinePrice) ||
                    (!trendline.isSupport() && closePrice >= trendlinePrice)) {
                supportResistanceCount++;
            } else {
                breakCount++;
            }
        }

        // Check the ratio of support/resistance interactions to breaks
        if (trendline.getSlope() < Math.toRadians(5)) {
            if (supportResistanceCount >= 2 * breakCount) {
                reliabilityScore += 20; // Give weight to trendlines with sufficient support/resistance
            }
        } else if (breakCount <= 2) {
            reliabilityScore += 20; // Favor trendlines that haven't been broken more than twice
        }

        // Factor 2: Recent support/resistance interactions
        int recentSupportResistanceCount = 0;
        int recentPeriod = Math.max(10, (lastPoint.getIndex() - firstPoint.getIndex()) / 3);

        for (int i = Math.max(0, lastPoint.getIndex() - recentPeriod); i <= lastPoint.getIndex(); i++) {
            double trendlinePrice = trendline.getSlope() * i + trendline.getIntercept();
            double closePrice = series.getBar(i).getClosePrice().doubleValue();

            if ((trendline.isSupport() && closePrice <= trendlinePrice) ||
                    (!trendline.isSupport() && closePrice >= trendlinePrice)) {
                recentSupportResistanceCount++;
            }
        }

        // Increase reliability score based on recent support/resistance
        reliabilityScore += recentSupportResistanceCount * 5;

        // Factor 3: Duration
        int duration = lastPoint.getIndex() - firstPoint.getIndex();
        reliabilityScore += duration * 0.5; // Weight for duration

        // Factor 4: Slope
        double slope = Math.abs(trendline.getSlope());
        if (slope > Math.toRadians(5) && slope <= Math.toRadians(45)) {
            reliabilityScore += 15; // Favor moderate slopes
        }

        // Factor 5: Volume at touch points
        double totalVolume = 0.0;
        for (BarTuple point : trendline.getPoints()) {
            totalVolume += series.getBar(point.getIndex()).getVolume().doubleValue();
        }
        reliabilityScore += totalVolume / trendline.getPoints().size(); // Average volume at touch points

        // Factor 6: Consistency (standard deviation of touch points around the trendline)
        double sumOfSquares = 0.0;
        for (BarTuple point : trendline.getPoints()) {
            double trendlinePrice = trendline.getSlope() * point.getIndex() + trendline.getIntercept();
            sumOfSquares += Math.pow(point.getBar().getClosePrice().doubleValue() - trendlinePrice, 2);
        }
        double variance = sumOfSquares / trendline.getPoints().size();
        double standardDeviation = Math.sqrt(variance);
        reliabilityScore += 10 / (standardDeviation + 1); // Higher consistency reduces standard deviation

        return reliabilityScore;
    }

}
