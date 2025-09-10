package com.dtech.chartdata.service;

import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.controller.BarSeriesHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChartDataService {

    private final BarSeriesHelper barSeriesHelper;

    /**
     * Returns OHLC bars for a symbol/interval. If from/to are provided (epoch seconds),
     * results are filtered to that range.
     */
    public List<OhlcBarDTO> getBars(String symbol, String interval, Long from, Long to) {
        Interval iv = Interval.valueOf(interval);
        IntervalBarSeries series = barSeriesHelper.getIntervalBarSeries(symbol, iv.name());
        if (series == null || series.isEmpty()) return List.of();

        long fromSec = from != null ? from : Long.MIN_VALUE;
        long toSec = to != null ? to : Long.MAX_VALUE;

        int end = series.getEndIndex();
        List<OhlcBarDTO> out = new ArrayList<>(end + 1);
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
        return out;
    }
}
