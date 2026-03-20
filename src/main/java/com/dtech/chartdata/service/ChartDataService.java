package com.dtech.chartdata.service;

import com.dtech.algo.runner.candle.LiveCandleCache;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.controller.BarSeriesHelper;
import com.dtech.kitecon.service.MarketHoursService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Serves OHLC bars to the frontend.
 *
 * During market hours: returns DB candles (all completed bars) + live partial
 * candle from LiveCandleCache appended at the end. This ensures the chart always
 * shows the latest tick even before the current bar closes to DB.
 *
 * Outside market hours: returns DB bars only.
 */
@Service
@RequiredArgsConstructor
public class ChartDataService {

    private final BarSeriesHelper barSeriesHelper;
    private final LiveCandleCache liveCandleCache;
    private final MarketHoursService marketHoursService;

    /**
     * Returns OHLC bars for a symbol/interval.
     * If from/to are provided (epoch seconds), results are filtered to that range.
     */
    public List<OhlcBarDTO> getBars(String symbol, String interval, Long from, Long to) {
        Interval iv = Interval.valueOf(interval);
        IntervalBarSeries series = barSeriesHelper.getIntervalBarSeries(symbol, iv.name());

        long fromSec = from != null ? from : Long.MIN_VALUE;
        long toSec   = to   != null ? to   : Long.MAX_VALUE;

        List<OhlcBarDTO> out = new ArrayList<>();

        if (series != null && !series.isEmpty()) {
            int end = series.getEndIndex();
            for (int i = 0; i <= end; i++) {
                long t = series.getBar(i).getEndTime().getEpochSecond();
                if (t < fromSec || t > toSec) continue;
                out.add(new OhlcBarDTO(
                        t,
                        series.getBar(i).getOpenPrice().doubleValue(),
                        series.getBar(i).getHighPrice().doubleValue(),
                        series.getBar(i).getLowPrice().doubleValue(),
                        series.getBar(i).getClosePrice().doubleValue(),
                        series.getBar(i).getVolume().doubleValue()
                ));
            }
        }

        // Append live (partial) candle during market hours
        if (marketHoursService.isMarketOpenNow()) {
            OhlcBarDTO liveBar = liveCandleCache.getLiveCandle(symbol, iv);
            if (liveBar != null) {
                long liveTime = liveBar.getTime();
                if (liveTime >= fromSec && liveTime <= toSec) {
                    // Replace last bar if same timestamp (tick update on last closed bar edge)
                    // or append as new bar
                    boolean replaced = false;
                    if (!out.isEmpty() && out.get(out.size() - 1).getTime() == liveTime) {
                        out.set(out.size() - 1, liveBar);
                        replaced = true;
                    }
                    if (!replaced) {
                        out.add(liveBar);
                    }
                }
            }
        }

        return out;
    }
}
