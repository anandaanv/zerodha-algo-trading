package com.dtech.ta;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.ta4j.core.Bar;

@EqualsAndHashCode(of = {"index", "ohlcType"})
public class BarTuple {
    private int index;
    private Bar bar;
    @Getter
    private OHLC ohlcType;

    public int getIndex() {
        return this.index;
    }

    public Bar getBar() {
        return this.bar;
    }

    public BarTuple(int index, Bar bar) {
        this.index = index;
        this.bar = bar;
        ohlcType = OHLC.C;
    }

    public BarTuple(int index, Bar bar, OHLC ohlcType) {
        this.index = index;
        this.bar = bar;
        this.ohlcType = ohlcType;
    }

    public double getValue() {
        switch (ohlcType) {
            case O:
                return bar.getOpenPrice().doubleValue();
            case H:
                return bar.getHighPrice().doubleValue();
            case L:
                return bar.getLowPrice().doubleValue();
            case C:
                return bar.getClosePrice().doubleValue();
            default:
                throw new IllegalStateException("Unexpected OHLC type: " + ohlcType);
        }
    }

    public String toString() {
        return index + " " + bar.getEndTime().toString() + " " + getValue();
    }
}
