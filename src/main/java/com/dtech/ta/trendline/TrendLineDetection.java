package com.dtech.ta.trendline;

import com.dtech.ta.BarTuple;
import com.dtech.ta.OHLC;
import com.dtech.ta.TrendLineCalculated;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Bar;
import java.util.ArrayList;
import java.util.List;

public class TrendLineDetection {

    private BarSeries barSeries;
    private double pivotSeparationThreshold;
    private double pivotGroupingThreshold;
    private double maxAllowableErrorPtToTrend;
    private double breakoutTolerance;

    public TrendLineDetection(BarSeries barSeries) {
        this.barSeries = barSeries;
        this.pivotSeparationThreshold = Util.avgCandleRange(barSeries) * 0.2;
        this.pivotGroupingThreshold = Util.avgCandleRange(barSeries) * 0.1;
        this.maxAllowableErrorPtToTrend = Util.avgCandleRange(barSeries) * 0.06;
        this.breakoutTolerance = Util.avgCandleRange(barSeries) * 0.08;
    }

    public List<TrendLineCalculated> detectTrendlines(TrendlineType trendType, boolean isSupport) {
        List<TrendLineCalculated> trendLines = new ArrayList<>();
        List<Integer> pivots = getPivots(trendType, isSupport);
        for (int i = 0; i < pivots.size() - 1; i++) {
            int startIndex = pivots.get(i);
            int endIndex = pivots.get(i + 1);

            Bar startBar = barSeries.getBar(startIndex);
            Bar endBar = barSeries.getBar(endIndex);

            double startPrice = getPrice(startBar, isSupport);
            double endPrice = getPrice(endBar, isSupport);

            double slope = (endPrice - startPrice) / (endIndex - startIndex);
            double intercept = startPrice - slope * startIndex;

            List<BarTuple> points = getTrendlinePoints(startIndex, endIndex, slope, intercept);

            if (!points.isEmpty()) {
                TrendLineCalculated trendLine = new TrendLineCalculated(barSeries, slope, intercept, points, isSupport);
                trendLines.add(trendLine);
            }
        }

        return trendLines;
    }

    private List<BarTuple> getTrendlinePoints(int startIndex, int endIndex, double slope, double intercept) {
        List<BarTuple> points = new ArrayList<>();

        for (int i = startIndex; i <= endIndex; i++) {
            Bar bar = barSeries.getBar(i);
            double calculatedPrice = slope * i + intercept;

            if (Math.abs(calculatedPrice - bar.getClosePrice().doubleValue()) < maxAllowableErrorPtToTrend) {
                points.add(new BarTuple(i, bar, OHLC.C));
            }
        }

        return points;
    }

    private List<Integer> getPivots(TrendlineType trendType, boolean isSupport) {
        List<Integer> pivots = new ArrayList<>();
        int firstIndex = 0;
        int lastIndex = barSeries.getBarCount() - 1;

        for (int i = firstIndex + 1; i < lastIndex; i++) {
            double currentPrice = getPrice(barSeries.getBar(i), isSupport);
            double prevPrice = getPrice(barSeries.getBar(i - 1), isSupport);
            double nextPrice = getPrice(barSeries.getBar(i + 1), isSupport);

            if (Math.abs(currentPrice - prevPrice) > pivotSeparationThreshold &&
                    Math.abs(currentPrice - nextPrice) > pivotSeparationThreshold) {
                pivots.add(i);
            }
        }

        pivots.add(firstIndex);
        pivots.add(lastIndex);

        return pivots;
    }

    private double getPrice(Bar bar, boolean isSupport) {
        return isSupport ? bar.getLowPrice().doubleValue() : bar.getHighPrice().doubleValue();
    }
}
