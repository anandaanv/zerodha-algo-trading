package com.dtech.ta;

import lombok.Data;
import org.ta4j.core.BarSeries;

import java.util.List;

@Data
public class TrendLineCalculated {
    private final BarSeries barSeries;
    private final double slope;
    private final double intercept;
    private final List<BarTuple> points;
    private final boolean isSupport;
    private double reliabilityScore;

    private double calculateSlope(int startIndex, int endIndex, boolean useHighs) {
        if (startIndex >= 0 && endIndex < barSeries.getBarCount()) {  // Ensure indices are valid
            double y1 = useHighs ? barSeries.getBar(startIndex).getHighPrice().doubleValue() : barSeries.getBar(startIndex).getLowPrice().doubleValue();
            double y2 = useHighs ? barSeries.getBar(endIndex).getHighPrice().doubleValue() : barSeries.getBar(endIndex).getLowPrice().doubleValue();
            return (y2 - y1) / (endIndex - startIndex);
        }
        return 0;  // Return a default slope if indices are out of bounds
    }

    private double calculateIntercept(int startIndex, double slope, boolean useHighs) {
        double y1 = useHighs ? barSeries.getBar(startIndex).getHighPrice().doubleValue() : barSeries.getBar(startIndex).getLowPrice().doubleValue();
        return y1 - slope * startIndex;
    }

    public double getPriceAt(int index) {
        return (1 + (slope * (index - points.get(0).getIndex()))) * points.get(0).getValue();
    }
    public double calculateYValue(double y) {
//        double lastVal = points.getLast().getValue();
//        return  (y - points.getLast().getIndex()) * slope * lastVal + lastVal;
        return getPriceAt(Double.valueOf(y).intValue());
    }

    public int getStartIndex() {
        return points.getFirst().getIndex();
    }

    public int getEndIndex() {
        return points.getLast().getIndex();
    }

    public String toString() {
        return points.toString();
    }
}
