package com.dtech.ta.elliott;

import com.dtech.ta.BarTuple;
import com.dtech.ta.OHLC;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import java.util.*;

public class RefinedLocalExtremesDetector {

    private BarSeries series;
    private int proximityThreshold;
    private int lookAroundCandles;
    private ATRIndicator atrIndicator;
    private MACDIndicator macdIndicator;

    public RefinedLocalExtremesDetector(BarSeries series, int lookAroundCandles, int proximityThreshold) {
        this.series = series;
        this.lookAroundCandles = lookAroundCandles;
        this.proximityThreshold = proximityThreshold;
        this.atrIndicator = new ATRIndicator(series, 14);  // 14-period ATR for volatility
        this.macdIndicator = new MACDIndicator(new ClosePriceIndicator(series), 12, 26);  // 12-26 MACD
    }

    // Core method to detect local extremes (highs and lows)
    public List<BarTuple> detectLocalExtremes() {
        // Detect both highs and lows together (support and resistance)
        List<BarTuple> convexHullPoints = findConvexHullPointsWithDynamicWindow(series);
        List<BarTuple> refinedPoints = refineConvexHullPoints(convexHullPoints);
        List<BarTuple> reducedPoints = reducePointsWithinProximity(refinedPoints);

        // Ensure alternation and insert missing lows/highs where necessary
        return ensureAlternationAndInsertMissingExtremes(reducedPoints);
    }

    // Method to remove similar points within a proximity range
    private List<BarTuple> reducePointsWithinProximity(List<BarTuple> points) {
        List<BarTuple> reducedPoints = new ArrayList<>();
        points.sort(Comparator.comparing(BarTuple::getIndex));  // Ensure points are sorted by index

        BarTuple previousPoint = null;
        for (BarTuple currentPoint : points) {
            if (previousPoint == null) {
                // Add the first point
                reducedPoints.add(currentPoint);
                previousPoint = currentPoint;
                continue;
            }

            // Check if the current point is within proximity of the previous point
            if (Math.abs(currentPoint.getIndex() - previousPoint.getIndex()) <= proximityThreshold) {
                // If both are highs (OHLC.H), keep the higher one
                if (previousPoint.getOhlcType() == OHLC.H && currentPoint.getOhlcType() == OHLC.H) {
                    previousPoint = (currentPoint.getValue() > previousPoint.getValue()) ? currentPoint : previousPoint;
                }
                // If both are lows (OHLC.L), keep the lower one
                else if (previousPoint.getOhlcType() == OHLC.L && currentPoint.getOhlcType() == OHLC.L) {
                    previousPoint = (currentPoint.getValue() < previousPoint.getValue()) ? currentPoint : previousPoint;
                }
            } else {
                // No proximity issue, add the previous point and update the previous point
                reducedPoints.add(previousPoint);
                previousPoint = currentPoint;
            }
        }

        // Add the last remaining point
        if (previousPoint != null) {
            reducedPoints.add(previousPoint);
        }

        return reducedPoints;
    }


    // Dynamically adjust window size based on volatility
    private List<BarTuple> findConvexHullPointsWithDynamicWindow(BarSeries series) {
        List<BarTuple> convexHullPoints = new ArrayList<>();
        int windowSize = calculateDynamicWindowSize();

        for (int i = 0; i <= series.getBarCount() - windowSize; i += windowSize / 2) {
            List<BarTuple> windowPoints = new ArrayList<>();
            for (int j = i; j < i + windowSize; j++) {
                // Add both high and low points for each bar
                BarTuple lowPoint = new BarTuple(j, series.getBar(j), OHLC.L);  // Low point (support)
                BarTuple highPoint = new BarTuple(j, series.getBar(j), OHLC.H);  // High point (resistance)
                windowPoints.add(lowPoint);
                windowPoints.add(highPoint);
            }
            convexHullPoints.addAll(findConvexHullPoints(windowPoints));
        }
        return convexHullPoints;
    }

    // Calculate dynamic window size based on volatility (ATR)
    private int calculateDynamicWindowSize() {
        Num atrValue = atrIndicator.getValue(series.getEndIndex());
        return Math.max((int) (atrValue.doubleValue() * 50), 200);  // Adjust 50x ATR with a min window of 200
    }

    // Refine convex hull points with look-around checks
    private List<BarTuple> refineConvexHullPoints(List<BarTuple> convexHullPoints) {
        List<BarTuple> refinedPoints = new ArrayList<>();
        for (BarTuple point : convexHullPoints) {
            int startIndex = Math.max(point.getIndex() - lookAroundCandles, 0);
            int endIndex = Math.min(point.getIndex() + lookAroundCandles, series.getBarCount() - 1);
            BarTuple localExtreme = findLocalMaxMinWithoutRSI(startIndex, endIndex, point.getOhlcType());
            refinedPoints.add(localExtreme);
        }
        return refinedPoints;
    }

    // Find local maximum or minimum
    private BarTuple findLocalMaxMinWithoutRSI(int startIndex, int endIndex, OHLC ohlcType) {
        double extremeValue = (ohlcType == OHLC.L) ? Double.MAX_VALUE : Double.MIN_VALUE;
        int extremeIndex = startIndex;

        for (int i = startIndex; i <= endIndex; i++) {
            double value = (ohlcType == OHLC.L) ? series.getBar(i).getLowPrice().doubleValue() : series.getBar(i).getHighPrice().doubleValue();
            boolean isExtreme = (ohlcType == OHLC.L && value < extremeValue) || (ohlcType == OHLC.H && value > extremeValue);

            // Check if it's the extreme value
            if (isExtreme) {
                extremeValue = value;
                extremeIndex = i;
            }
        }

        return new BarTuple(extremeIndex, series.getBar(extremeIndex), ohlcType);
    }

    // Ensure alternation between highs and lows, and insert missing lows or highs where necessary
    private List<BarTuple> ensureAlternationAndInsertMissingExtremes(List<BarTuple> points) {
        List<BarTuple> alternatingPoints = new ArrayList<>();
        points.sort(Comparator.comparing(BarTuple::getIndex));

        BarTuple previousPoint = null;
        for (int i = 0; i < points.size(); i++) {
            BarTuple currentPoint = points.get(i);

            // If there's no previous point, just add the first point
            if (previousPoint == null) {
                alternatingPoints.add(currentPoint);
                previousPoint = currentPoint;
                continue;
            }

            // If consecutive highs, insert the lowest point in between as a low
            if (previousPoint.getOhlcType() == OHLC.H && currentPoint.getOhlcType() == OHLC.H) {
                BarTuple lowestInBetween = findLowestBetween(previousPoint.getIndex(), currentPoint.getIndex());
                alternatingPoints.add(lowestInBetween);  // Insert the low
            }

            // If consecutive lows, insert the highest point in between as a high
            if (previousPoint.getOhlcType() == OHLC.L && currentPoint.getOhlcType() == OHLC.L) {
                BarTuple highestInBetween = findHighestBetween(previousPoint.getIndex(), currentPoint.getIndex());
                alternatingPoints.add(highestInBetween);  // Insert the high
            }

            alternatingPoints.add(currentPoint);
            previousPoint = currentPoint;
        }

        return replaceHighWithHigherHighLowerLows(alternatingPoints);

    }

    private List<BarTuple> replaceHighWithHigherHighLowerLows(List<BarTuple> alternatingPoints) {
        List<BarTuple> finalValue = new ArrayList<>();
        for(int i = 0; i<alternatingPoints.size() -1; i++) {
            BarTuple tuple1 = alternatingPoints.get(i);
            BarTuple tuple2 = alternatingPoints.get(i+1);
            if(tuple1.getOhlcType() == OHLC.L) {
                BarTuple tmp = findLowestBetween(tuple1.getIndex(), tuple2.getIndex());
                if(tmp.getValue() < tuple1.getValue()) {
                    finalValue.add(tmp);
                } else {
                    finalValue.add(tuple1);
                }
            } else {
                BarTuple tmp = findHighestBetween(tuple1.getIndex(), tuple2.getIndex());
                if(tmp.getValue() > tuple1.getValue()) {
                    finalValue.add(tmp);
                } else {
                    finalValue.add(tuple1);
                }
            }
        }
        finalValue.add(alternatingPoints.getLast());
        return finalValue;
    }

    // Find the lowest point between two indexes
    private BarTuple findLowestBetween(int startIndex, int endIndex) {
        double lowestValue = Double.MAX_VALUE;
        int lowestIndex = startIndex;

        for (int i = startIndex; i <= endIndex; i++) {
            double low = series.getBar(i).getLowPrice().doubleValue();
            if (low < lowestValue) {
                lowestValue = low;
                lowestIndex = i;
            }
        }

        return new BarTuple(lowestIndex, series.getBar(lowestIndex), OHLC.L);  // Return as a low
    }

    // Find the highest point between two indexes
    private BarTuple findHighestBetween(int startIndex, int endIndex) {
        double highestValue = Double.MIN_VALUE;
        int highestIndex = startIndex;

        for (int i = startIndex; i <= endIndex; i++) {
            double high = series.getBar(i).getHighPrice().doubleValue();
            if (high > highestValue) {
                highestValue = high;
                highestIndex = i;
            }
        }

        return new BarTuple(highestIndex, series.getBar(highestIndex), OHLC.H);  // Return as a high
    }

    // Convex Hull algorithm (Graham Scan)
    private List<BarTuple> findConvexHullPoints(List<BarTuple> points) {
        if (points.size() < 3) {
            return points;
        }
        points.sort(Comparator.comparing(BarTuple::getIndex).thenComparing(BarTuple::getValue));
        Stack<BarTuple> hull = new Stack<>();
        hull.push(points.get(0));
        hull.push(points.get(1));

        for (int i = 2; i < points.size(); i++) {
            BarTuple top = hull.pop();
            while (hull.size() > 0 && orientation(hull.peek(), top, points.get(i)) != 2) {
                top = hull.pop();
            }
            hull.push(top);
            hull.push(points.get(i));
        }
        return new ArrayList<>(hull);
    }

    // Orientation function for Graham Scan (checking left/right turn)
    private int orientation(BarTuple p, BarTuple q, BarTuple r) {
        double val = (q.getValue() - p.getValue()) * (r.getIndex() - q.getIndex()) - (q.getIndex() - p.getIndex()) * (r.getValue() - q.getValue());
        if (val == 0) return 0;  // Collinear
        return (val > 0) ? 1 : 2;  // Clockwise or counterclockwise
    }
}
