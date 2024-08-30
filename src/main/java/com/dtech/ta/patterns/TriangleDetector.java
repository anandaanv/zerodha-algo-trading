package com.dtech.ta.patterns;

import com.dtech.ta.BarTuple;
import com.dtech.ta.TrendLineCalculated;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

public class TriangleDetector {

    private final BarSeries series;
    private final int minPointsForTrendline = 8;  // Minimum points for trendline
    private final double minSlopeDifference = 0.1;  // Minimum slope difference
    private final int minTriangleDuration = 5;  // Minimum duration of triangle
    private final double maxFlatSlope = 0.02;  // Maximum slope for flat support line
    private final double minHighLowDifference = 0.05;  // Minimum difference between High1 and Low5

    public TriangleDetector(BarSeries series) {
        this.series = series;
    }

    public List<TrianglePattern> detectTriangles(int windowSize) {
        List<TrianglePattern> triangles = new ArrayList<>();

        for (int i = windowSize; i < series.getBarCount(); i++) {
            if (isInDowntrend(i - windowSize, i)) {
                List<TrendLineCalculated> highTrendlines = findAllHighTrendlines(windowSize, i);
                List<TrendLineCalculated> lowTrendlines = findAllLowTrendlines(windowSize, i);

                for (TrendLineCalculated highTrendline : highTrendlines) {
                    for (TrendLineCalculated lowTrendline : lowTrendlines) {
                        if (isDescendingTriangle(highTrendline, lowTrendline) && meetsHighLowDifferenceCondition(highTrendline, lowTrendline)) {
                            TrianglePattern triangle = new TrianglePattern(highTrendline, lowTrendline);
                            if (isValidTriangle(triangle)) {
                                triangles.add(triangle);
                            }
                        }
                    }
                }
            }
        }

        return triangles;
    }

    private boolean isInDowntrend(int startIndex, int endIndex) {
        double startPrice = series.getBar(startIndex).getClosePrice().doubleValue();
        double endPrice = series.getBar(endIndex).getClosePrice().doubleValue();
        return endPrice < startPrice;  // Simple check for downtrend
    }

    private boolean meetsHighLowDifferenceCondition(TrendLineCalculated highTrendline, TrendLineCalculated lowTrendline) {
        double high1 = highTrendline.getPoints().get(0).getBar().getHighPrice().doubleValue();
        double low5 = lowTrendline.getPoints().get(lowTrendline.getPoints().size() - 1).getBar().getLowPrice().doubleValue();
        return (high1 - low5) >= minHighLowDifference * high1;  // Ensure sufficient difference between High1 and Low5
    }

    private List<TrendLineCalculated> findAllHighTrendlines(int windowSize, int currentEndIndex) {
        List<TrendLineCalculated> highTrendlines = new ArrayList<>();

        for (int i = currentEndIndex - windowSize + 1; i <= currentEndIndex; i++) {
            TrendLineCalculated highTrendline = findTrendline(i, windowSize, true);
            if (highTrendline != null && highTrendline.getPoints().size() >= minPointsForTrendline) {
                highTrendlines.add(highTrendline);
            }
        }

        return highTrendlines;
    }

    private List<TrendLineCalculated> findAllLowTrendlines(int windowSize, int currentEndIndex) {
        List<TrendLineCalculated> lowTrendlines = new ArrayList<>();

        for (int i = currentEndIndex - windowSize + 1; i <= currentEndIndex; i++) {
            TrendLineCalculated lowTrendline = findTrendline(i, windowSize, false);
            if (lowTrendline != null && lowTrendline.getPoints().size() >= minPointsForTrendline) {
                lowTrendlines.add(lowTrendline);
            }
        }

        return lowTrendlines;
    }

    private TrendLineCalculated findTrendline(int endIndex, int windowSize, boolean useHighs) {
        int startIndex = Math.max(0, endIndex - windowSize + 1);  // Ensure startIndex is not negative
        for (int i = startIndex; i < endIndex; i++) {
            for (int j = i + 1; j <= endIndex; j++) {
                if (i >= 0 && j < series.getBarCount()) {  // Ensure indices are within valid range
                    double slope = calculateSlope(i, j, useHighs);
                    double intercept = calculateIntercept(i, slope, useHighs);
                    List<BarTuple> points = new ArrayList<>();

                    boolean valid = true;
                    for (int k = startIndex; k <= endIndex; k++) {
                        if (k >= 0 && k < series.getBarCount()) {  // Ensure index k is within valid range
                            double price = useHighs ? series.getBar(k).getHighPrice().doubleValue() : series.getBar(k).getLowPrice().doubleValue();
                            double expectedPrice = slope * (k - i) + intercept;
                            if (Math.abs(price - expectedPrice) > price * 0.01) {
                                valid = false;
                                break;
                            }
                            points.add(new BarTuple(k, series.getBar(k)));
                        } else {
                            valid = false;
                            break;
                        }
                    }

                    if (valid && points.size() >= minPointsForTrendline) {
                        return new TrendLineCalculated(series, slope, intercept, points, !useHighs);
                    }
                }
            }
        }
        return null;
    }

    private double calculateSlope(int startIndex, int endIndex, boolean useHighs) {
        if (startIndex >= 0 && endIndex < series.getBarCount()) {  // Ensure indices are valid
            double y1 = useHighs ? series.getBar(startIndex).getHighPrice().doubleValue() : series.getBar(startIndex).getLowPrice().doubleValue();
            double y2 = useHighs ? series.getBar(endIndex).getHighPrice().doubleValue() : series.getBar(endIndex).getLowPrice().doubleValue();
            return (y2 - y1) / (endIndex - startIndex);
        }
        return 0;  // Return a default slope if indices are out of bounds
    }

    private double calculateIntercept(int startIndex, double slope, boolean useHighs) {
        double y1 = useHighs ? series.getBar(startIndex).getHighPrice().doubleValue() : series.getBar(startIndex).getLowPrice().doubleValue();
        return y1 - slope * startIndex;
    }

    private boolean isDescendingTriangle(TrendLineCalculated highTrendline, TrendLineCalculated lowTrendline) {
        double slopeDifference = Math.abs(highTrendline.getSlope() - lowTrendline.getSlope());
        return highTrendline.getSlope() < -minSlopeDifference && Math.abs(lowTrendline.getSlope()) < maxFlatSlope && slopeDifference >= minSlopeDifference;
    }

    private boolean isValidTriangle(TrianglePattern triangle) {
        // Ensure the triangle covers a significant duration
        int duration = triangle.getHighTrendline().getEndIndex() - triangle.getLowTrendline().getStartIndex();
        if (duration < minTriangleDuration) {
            return false;
        }
        // Additional validation checks can be added here
        return true;
    }
}
