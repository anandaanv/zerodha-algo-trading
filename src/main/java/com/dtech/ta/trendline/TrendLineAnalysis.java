package com.dtech.ta.trendline;

import com.dtech.ta.BarTuple;
import com.dtech.ta.TrendLineCalculated;
import com.dtech.ta.OHLC;
import org.ta4j.core.BarSeries;

import java.util.*;

public class TrendLineAnalysis {

    // Public method to calculate both low (support) and high (resistance) trendlines
    public List<TrendLineCalculated> calculateTrendlines(BarSeries series, boolean isSupport, boolean validateActive) {
        List<BarTuple> convexHullPoints = isSupport ? findConvexHullLows(series) : findConvexHullHighs(series);
        List<BarTuple> slidingWindowPoints = isSupport ? findSlidingWindowLows(series, 300) : findSlidingWindowHighs(series, 300);
        Set<BarTuple> uniquePoints = new HashSet<>(slidingWindowPoints);
        slidingWindowPoints = new ArrayList<>(uniquePoints);

        List<BarTuple> combinedPoints = new ArrayList<>(convexHullPoints);
        combinedPoints.addAll(slidingWindowPoints);
        combinedPoints.sort(Comparator.comparing(BarTuple::getIndex));

        return generateTrendlines(series, combinedPoints, isSupport, validateActive);
    }

    // Generate all combinations of two points and calculate trendlines
    private List<TrendLineCalculated> generateTrendlines(BarSeries series, List<BarTuple> points, boolean isSupport, boolean validateActive) {
        List<TrendLineCalculated> trendlines = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                BarTuple point1 = points.get(i);
                BarTuple point2 = points.get(j);

                double percentChangePrice = (point2.getValue() - point1.getValue()) / point1.getValue();
                double percentChangeTime = (point2.getIndex() - point1.getIndex());
                double slope = percentChangePrice / percentChangeTime;
                double intercept = point1.getValue() - slope * point1.getIndex();

                TrendLineCalculated trendline = new TrendLineCalculated(series, slope, intercept, List.of(point1, point2), isSupport);

                if (!isTrendlineBroken(trendline, series, points, isSupport)) {
                    trendlines.add(trendline);
                }
            }
        }
        return trendlines;
    }

    // Find lows that fall on the convex hull using the Graham Scan algorithm
    private List<BarTuple> findConvexHullLows(BarSeries series) {
        return findConvexHullPoints(series, OHLC.L);
    }

    // Find highs that fall on the convex hull using the Graham Scan algorithm
    private List<BarTuple> findConvexHullHighs(BarSeries series) {
        return findConvexHullPoints(series, OHLC.H);
    }

    // Generalized method to find points that fall on the convex hull (can be used for both highs and lows)
    private List<BarTuple> findConvexHullPoints(BarSeries series, OHLC ohlcType) {
        List<BarTuple> points = new ArrayList<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            points.add(new BarTuple(i, series.getBar(i), ohlcType));
        }

        if (points.size() < 3) {
            return points;  // Edge case: fewer than 3 points
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

    // Find local lows using a sliding window approach
    private List<BarTuple> findSlidingWindowLows(BarSeries series, int windowSize) {
        return findSlidingWindowPoints(series, windowSize, OHLC.L);
    }

    // Find local highs using a sliding window approach
    private List<BarTuple> findSlidingWindowHighs(BarSeries series, int windowSize) {
        return findSlidingWindowPoints(series, windowSize, OHLC.H);
    }

    // Generalized method to find sliding window points (can be used for both highs and lows)
    private List<BarTuple> findSlidingWindowPoints(BarSeries series, int windowSize, OHLC ohlcType) {
        List<BarTuple> windowPoints = new ArrayList<>();

        for (int i = 0; i <= series.getBarCount() - windowSize; i += 30) {
            double extremeValue = (ohlcType == OHLC.L) ? Double.MAX_VALUE : Double.MIN_VALUE;
            int extremeIndex = i;

            for (int j = i; j < i + windowSize; j++) {
                double value = (ohlcType == OHLC.L) ? series.getBar(j).getLowPrice().doubleValue() : series.getBar(j).getHighPrice().doubleValue();
                if ((ohlcType == OHLC.L && value < extremeValue) || (ohlcType == OHLC.H && value > extremeValue)) {
                    extremeValue = value;
                    extremeIndex = j;
                }
            }

            windowPoints.add(new BarTuple(extremeIndex, series.getBar(extremeIndex), ohlcType));
        }

        return windowPoints;
    }

    // Check if the trendline is broken in the next 10 candles or forms a new extreme point
    private boolean isTrendlineBroken(TrendLineCalculated trendline, BarSeries series, List<BarTuple> combinedPoints, boolean isSupportTrendline) {
        int startIndex = trendline.getStartIndex();
        int seriesEndIndex = series.getBarCount() - 1;
        int stayBelowThreshold = 10;  // Number of candles the price must stay below the trendline
        int belowCount = 0;

        for (int i = startIndex; i <= seriesEndIndex; i++) {
            double trendPrice = trendline.getPriceAt(i);
            double currentPrice = series.getBar(i).getClosePrice().doubleValue();

            if (isSupportTrendline) {
                if (currentPrice < trendPrice) {
                    belowCount++;
                    for (BarTuple low : combinedPoints) {
                        if (low.getIndex() == i && currentPrice < low.getValue()) {
                            return true;
                        }
                    }
                    if (belowCount >= stayBelowThreshold) {
                        return true;
                    }
                } else {
                    belowCount = 0;
                }
            } else {
                if (currentPrice > trendPrice) {
                    belowCount++;
                    for (BarTuple high : combinedPoints) {
                        if (high.getIndex() == i && currentPrice > high.getValue()) {
                            return true;
                        }
                    }
                    if (belowCount >= stayBelowThreshold) {
                        return true;
                    }
                } else {
                    belowCount = 0;
                }
            }
        }

        return false;
    }

    // Calculate the orientation of three points: 0 = collinear, 1 = clockwise, 2 = counterclockwise
    private int orientation(BarTuple p, BarTuple q, BarTuple r) {
        double val = (q.getValue() - p.getValue()) * (r.getIndex() - q.getIndex()) -
                (q.getIndex() - p.getIndex()) * (r.getValue() - q.getValue());

        if (val == 0.0) return 0; // collinear
        return (val > 0.0) ? 1 : 2; // clockwise or counterclockwise
    }
}
