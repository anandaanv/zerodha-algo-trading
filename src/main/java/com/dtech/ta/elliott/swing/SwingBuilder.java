package com.dtech.ta.elliott.swing;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SwingBuilder {

    /**
     * Build swings from an ordered list of ZigZagPoints.
     * A swing is the move from pivot[i] to pivot[i+1].
     */
    public List<Swing> build(List<ZigZagPoint> pivots, String symbol, String timeframe) {
        List<Swing> result = new ArrayList<>();
        if (pivots == null || pivots.size() < 2) return result;

        for (int i = 0; i < pivots.size() - 1; i++) {
            ZigZagPoint from = pivots.get(i);
            ZigZagPoint to = pivots.get(i + 1);

            double startPrice = from.getValue();
            double endPrice = to.getValue();
            double priceChange = endPrice - startPrice;
            double percentChange = Math.abs(priceChange / startPrice) * 100.0;
            int barCount = to.getBarIndex() - from.getBarIndex();
            double slope = priceChange / Math.max(barCount, 1);
            String direction = priceChange >= 0 ? "UP" : "DOWN";
            String id = symbol + "_" + timeframe + "_" + from.getBarIndex() + "_" + to.getBarIndex();

            long startTs = from.getTimestamp() != null ? from.getTimestamp().toEpochMilli() : 0L;
            long endTs = to.getTimestamp() != null ? to.getTimestamp().toEpochMilli() : 0L;

            result.add(Swing.builder()
                    .id(id)
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .startPivotIndex(from.getBarIndex())
                    .endPivotIndex(to.getBarIndex())
                    .startTimestamp(startTs)
                    .endTimestamp(endTs)
                    .startPrice(startPrice)
                    .endPrice(endPrice)
                    .direction(direction)
                    .priceChange(priceChange)
                    .percentChange(percentChange)
                    .barCount(barCount)
                    .slope(slope)
                    .build());
        }
        return result;
    }
}
