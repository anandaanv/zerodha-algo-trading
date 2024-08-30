//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.dtech.ta;

import java.util.ArrayList;
import java.util.List;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.helpers.PriceIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.num.Num;

public class TrendLineUpside extends CachedIndicator<Num> {
    private final PriceIndicator indicator;

    protected TrendLineUpside(PriceIndicator indicator) {
        super(indicator);
        this.indicator = indicator;
    }

    public Num getValue(int index) {
        BarTuple[] peakBars = this.findPeakBars();
        double[] indexes = new double[peakBars.length];
        double[] values = new double[peakBars.length];

        for(int i = 0; i < peakBars.length; ++i) {
            int indexValue = peakBars[i].getIndex();
            indexes[i] = (double)(index - indexValue);
            values[i] = ((Num)this.indicator.getValue(peakBars[i].getIndex())).doubleValue();
        }

        double slope = this.calculateSlope(indexes, values);
        return DecimalNum.valueOf(this.extrapolateSlopeOnY(values[0], indexes[0], (double)(this.getBarSeries().getEndIndex() - index), slope));
    }

    protected double calculateSlope(double[] indexes, double[] values) {
        double startX = indexes[0];
        double startY = values[0];
        double slope = 0.0;

        for(int i = 1; i < indexes.length; ++i) {
            slope += (startX - indexes[i]) / (startY - values[i]);
        }

        return slope / (double)(indexes.length - 1);
    }

    protected Num calculate(int index) {
        return this.getValue(index);
    }

    protected BarTuple[] findPeakBars() {
        EMAIndicator emaIndicator = new EMAIndicator(this.indicator, 5);
        int size = this.getBarSeries().getEndIndex();
        List<BarTuple> allPeakBars = new ArrayList();
        int endIndex = size < 250 ? 0 : size - 250;

        for(int index = size; index > endIndex; --index) {
            if (this.isPeak(emaIndicator, index)) {
                allPeakBars.add(new BarTuple(index, this.getBarSeries().getBar(index)));
            }
        }

        Num previousHigh = DoubleNum.valueOf(0);
        List<BarTuple> higherHighs = new ArrayList();

        for(int i = 0; i < allPeakBars.size(); ++i) {
            Num value = (Num)this.indicator.getValue(((BarTuple)allPeakBars.get(i)).getIndex());
            if (value.doubleValue() > ((Num)previousHigh).doubleValue()) {
                higherHighs.add((BarTuple)allPeakBars.get(i));
                previousHigh = value;
            }
        }

        return (BarTuple[])higherHighs.toArray(new BarTuple[higherHighs.size()]);
    }

    private boolean isPeak(EMAIndicator emaIndicator, int index) {
        BarSeries barSeries = emaIndicator.getBarSeries();

        for(int i = 1; i <= 4; ++i) {
            if (barSeries.getEndIndex() < index + i || ((Num)emaIndicator.getValue(index)).isLessThan((Num)emaIndicator.getValue(index + i)) || index - i < 0 || ((Num)emaIndicator.getValue(index)).isLessThan((Num)emaIndicator.getValue(index - i))) {
                return false;
            }
        }

        return true;
    }

    public double extrapolateSlopeOnY(double y2, double x2, double x1, double slope) {
        double xDiff = x2 - x1;
        return y2 - xDiff / slope;
    }
}
