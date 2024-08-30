package com.dtech.ta.patterns;

import com.dtech.ta.TrendLineCalculated;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FlagPattern {
    private final int startIndex;
    private final int endIndex;
    private final TrendLineCalculated highTrendline;
    private final TrendLineCalculated lowTrendline;

    public String toString() {
        return highTrendline.getPoints().getFirst().getBar().getDateName() + " "
                + highTrendline.getPoints().getLast().getBar().getDateName();
    }
}
