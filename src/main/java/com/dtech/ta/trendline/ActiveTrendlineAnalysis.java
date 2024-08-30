package com.dtech.ta.trendline;

import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.ta.BarTuple;
import com.dtech.ta.OHLC;
import com.dtech.ta.TrendLineCalculated;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.*;

@Service
public class ActiveTrendlineAnalysis implements TrendlineAnalyser {

    private static final double PRICE_TOLERANCE_PERCENTAGE = 0.02;  // 2% tolerance for proximity to current price
    private static final int MAX_BROKEN_POINTS = 3;  // Max points allowed outside trendline

    // Main method to analyze both support and resistance trendlines
    public List<TrendLineCalculated> analyze(IntervalBarSeries series, boolean validateActive) {
        List<TrendLineCalculated> trendlines = calculateActiveTrendlines(series, true, validateActive);  // Support trendlines
        trendlines.addAll(calculateActiveTrendlines(series, false, validateActive));  // Resistance trendlines
        return trendlines;
    }

    // Method to calculate active trendlines for support or resistance
    public List<TrendLineCalculated> calculateActiveTrendlines(BarSeries series, boolean isSupport, boolean validateActive) {
        List<BarTuple> combinedPoints = getCombinedHighLows(series, isSupport);
        return filterActiveTrendlines(series, combinedPoints, isSupport, validateActive);
    }

    // Extract combined high/lows using convex hull and sliding window
    public List<BarTuple> getCombinedHighLows(BarSeries series) {
        List<BarTuple> combinedHighLows = getCombinedHighLows(series, true);  // Support points
        combinedHighLows.addAll(getCombinedHighLows(series, false));  // Resistance points
        return combinedHighLows;
    }

    @NotNull
    public List<BarTuple> getCombinedHighLows(BarSeries series, boolean isSupport) {
        List<BarTuple> convexHullPoints = findConvexHullPointsWithSlidingWindow(series, isSupport, 300);
        List<BarTuple> refinedPoints = refineConvexHullPoints(convexHullPoints, series, 50); // Look 5 candles in both directions

        // Remove duplicates and sort points by index
        Set<BarTuple> uniquePoints = new HashSet<>(refinedPoints);
        List<BarTuple> combinedPoints = new ArrayList<>(uniquePoints);
        combinedPoints.sort(Comparator.comparing(BarTuple::getIndex));
        return combinedPoints;
    }

    // Find convex hull points with a sliding window
    private List<BarTuple> findConvexHullPointsWithSlidingWindow(BarSeries series, boolean isSupport, int windowSize) {
        List<BarTuple> convexHullPoints = new ArrayList<>();
        for (int i = 0; i <= series.getBarCount() - windowSize; i += 100) {
            List<BarTuple> windowPoints = new ArrayList<>();
            for (int j = i; j < i + windowSize; j++) {
                BarTuple point = new BarTuple(j, series.getBar(j), isSupport ? OHLC.L : OHLC.H);
                windowPoints.add(point);
            }
            convexHullPoints.addAll(findConvexHullPoints(windowPoints));
        }
        return convexHullPoints;
    }

    // Refine convex hull points to nearby maxima or minima
    private List<BarTuple> refineConvexHullPoints(List<BarTuple> convexHullPoints, BarSeries series, int lookAroundCandles) {
        List<BarTuple> refinedPoints = new ArrayList<>();
        for (BarTuple point : convexHullPoints) {
            int startIndex = Math.max(point.getIndex() - lookAroundCandles, 0);
            int endIndex = Math.min(point.getIndex() + lookAroundCandles, series.getBarCount() - 1);
            BarTuple localExtreme = findLocalMaxMin(series, startIndex, endIndex, point.getOhlcType());
            refinedPoints.add(localExtreme);
        }
        return refinedPoints;
    }

    // Find local maximum or minimum in the given range
    private BarTuple findLocalMaxMin(BarSeries series, int startIndex, int endIndex, OHLC ohlcType) {
        double extremeValue = (ohlcType == OHLC.L) ? Double.MAX_VALUE : Double.MIN_VALUE;
        int extremeIndex = startIndex;

        for (int i = startIndex; i <= endIndex; i++) {
            double value = (ohlcType == OHLC.L) ? series.getBar(i).getLowPrice().doubleValue() : series.getBar(i).getHighPrice().doubleValue();
            if ((ohlcType == OHLC.L && value < extremeValue) || (ohlcType == OHLC.H && value > extremeValue)) {
                extremeValue = value;
                extremeIndex = i;
            }
        }
        return new BarTuple(extremeIndex, series.getBar(extremeIndex), ohlcType);
    }

    // Find convex hull points using the Graham Scan algorithm
    private List<BarTuple> findConvexHullPoints(List<BarTuple> points) {
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

    private int orientation(BarTuple p, BarTuple q, BarTuple r) {
        double val = (q.getValue() - p.getValue()) * (r.getIndex() - q.getIndex()) -
                (q.getIndex() - p.getIndex()) * (r.getValue() - q.getValue());

        if (val == 0.0) return 0; // collinear
        return (val > 0.0) ? 1 : 2; // clockwise or counterclockwise
    }

    // Calculate valid trendlines by filtering based on slope and proximity
    private List<TrendLineCalculated> filterActiveTrendlines(BarSeries series, List<BarTuple> points, boolean isSupport, boolean validateActive) {
        List<TrendLineCalculated> activeTrendlines = new ArrayList<>();
        int currentIndex = series.getBarCount() - 1;  // Current (last) bar in the series
        double currentClosePrice = series.getBar(currentIndex).getClosePrice().doubleValue();

        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                BarTuple point1 = points.get(i);
                BarTuple point2 = points.get(j);

                // Slope validation: For support, slope must be positive, for resistance, it must be negative
                double slope = calculateSlope(point1, point2);
                if ((isSupport && slope < 0) || (!isSupport && slope > 0)) {
                    continue;  // Skip if slope is invalid
                }

                double intercept = calculateIntercept(point1, slope);
                TrendLineCalculated trendline = new TrendLineCalculated(series, slope, intercept, List.of(point1, point2), isSupport);

                // Check if the trendline is unbroken and close to the current price
                if (isValidTrendline(trendline, currentIndex, currentClosePrice, isSupport) && isUnbrokenAndClose(trendline, currentIndex, series, points, validateActive)) {
                    TrendlineTAConfirmation entry = new TrendlineTAConfirmation();
                    if (entry.validate(series, currentIndex, isSupport)) {
                        activeTrendlines.add(trendline);
                    }
                }
            }
        }
        return activeTrendlines;
    }

    // Calculate slope between two points
    private double calculateSlope(BarTuple point1, BarTuple point2) {
        double percentChangePrice = (point2.getValue() - point1.getValue()) / point1.getValue();
        double percentChangeTime = (point2.getIndex() - point1.getIndex());
        return percentChangePrice / percentChangeTime;
    }

    // Calculate intercept using slope and point1
    private double calculateIntercept(BarTuple point1, double slope) {
        return point1.getValue() - slope * point1.getIndex();
    }

    // Check if the trendline is valid based on price proximity and not being broken
    private boolean isValidTrendline(TrendLineCalculated trendline, int currentIndex, double currentClosePrice, boolean isSupportTrendline) {
        double trendPrice = trendline.getPriceAt(currentIndex);  // Price at the current index based on the trendline
        double tolerance = PRICE_TOLERANCE_PERCENTAGE * currentClosePrice;  // Tolerance for proximity

        // Check if the current price is within the tolerance range
        return Math.abs(currentClosePrice - trendPrice) <= tolerance;
    }

    // Check if the trendline is unbroken and close to the current price
    private boolean isUnbrokenAndClose(TrendLineCalculated trendline, int currentIndex, BarSeries series, List<BarTuple> localExtremes, boolean validateActive) {
        int brokenExtremes = 0;

        for (BarTuple extreme : localExtremes) {
            int extremeIndex = extreme.getIndex();
            if (extremeIndex > trendline.getStartIndex() && extremeIndex <= currentIndex) {
                double trendPrice = trendline.getPriceAt(extremeIndex);
                double extremeValue = extreme.getValue();

                // Check if the extreme is outside the trendline (above resistance or below support)
                if (validateActive && ((trendline.isSupport() && extremeValue < trendPrice) ||
                        (!trendline.isSupport() && extremeValue > trendPrice))) {

                    brokenExtremes++;

                    // Invalidate trendline if more than 2 local extremes are outside the trendline
                    if (brokenExtremes >= 2) {
                        return false;  // Trendline is invalid
                    }
                }
            }
        }
        return true;  // Trendline is valid and unbroken
    }


    // Calculate ATR (Average True Range) for the given index
    private double calculateATR(BarSeries series, int index) {
        // You can implement an ATR calculation here
        // For now, we will assume a constant ATR value for simplicity
        return 0.05;  // Assuming ATR is 5% for simplicity
    }

}
