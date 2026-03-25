package com.dtech.ta.elliott.swing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Swing {
    private String id;
    private String symbol;
    private String timeframe;
    private int startPivotIndex;
    private int endPivotIndex;
    private long startTimestamp;
    private long endTimestamp;
    private double startPrice;
    private double endPrice;
    private String direction;       // "UP" or "DOWN"
    private double priceChange;     // endPrice - startPrice (signed)
    private double percentChange;   // abs((endPrice-startPrice)/startPrice)*100
    private int barCount;           // endPivotIndex - startPivotIndex
    private double slope;           // priceChange / barCount
}
