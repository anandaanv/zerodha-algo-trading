package com.dtech.ta.divergences;

import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import java.util.List;

public abstract class DivergenceDetector {
    protected final BarSeries series;

    public DivergenceDetector(BarSeries series) {
        this.series = series;
    }

    // Abstract method to detect divergences and return a list
    public abstract List<Divergence> detectDivergences();

    protected boolean isPricePeak(int index) {
        return series.getBar(index).getHighPrice().isGreaterThan(series.getBar(index - 1).getHighPrice())
                && series.getBar(index).getHighPrice().isGreaterThan(series.getBar(index + 1).getHighPrice());
    }

    protected boolean isPriceTrough(int index) {
        return series.getBar(index).getLowPrice().isLessThan(series.getBar(index - 1).getLowPrice())
                && series.getBar(index).getLowPrice().isLessThan(series.getBar(index + 1).getLowPrice());
    }

    protected boolean isConfirmed(int startIndex, int confirmationPeriod, boolean isPrice) {
        // Ensure no reverse crossover/movement in confirmation period
        for (int i = startIndex; i < startIndex + confirmationPeriod; i++) {
            if (isPrice) {
                if (isPricePeak(i) || isPriceTrough(i)) {
                    return false;
                }
            } else {
                if (series.getBar(i).getClosePrice().isGreaterThan(series.getBar(i - 1).getClosePrice())) {
                    return false;
                }
            }
        }
        return true;
    }
}
