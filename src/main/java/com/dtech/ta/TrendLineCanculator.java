package com.dtech.ta;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.num.Num;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class TrendLineCanculator {
    @Getter
    private final BarSeries barSeries;
    private OHLC ohlc;
//
//    public List<TrendLineCalculated> calculateTrendLines() {
//        BarTuple[] peakBars = findPeakBars();
//        return peakBars;
//    }

    protected BarTuple[] findPeakBars() {
        EMAIndicator emaIndicator = new EMAIndicator(getIndicator(), 5);
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
            BarTuple barTuple = allPeakBars.get(i);
            Num value = (Num)getIndicator().getValue(barTuple.getIndex());
            if (value.doubleValue() > ((Num)previousHigh).doubleValue()) {
                higherHighs.add(barTuple);
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

    private PriceIndicator getIndicator() {
        switch (ohlc) {
            case C -> {
                return new ClosePriceIndicator(barSeries);
            }
            case O -> {
                return new OpenPriceIndicator(barSeries);
            }
            case L -> {
                return new LowPriceIndicator(barSeries);
            }
            default -> {
                return new HighPriceIndicator(barSeries);
            }
        }
    }
}


