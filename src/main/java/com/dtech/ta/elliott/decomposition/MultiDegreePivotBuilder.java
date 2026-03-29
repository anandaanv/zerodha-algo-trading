package com.dtech.ta.elliott.decomposition;

import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MultiDegreePivotBuilder {

    private static final double FINE_ATR_MULT   = 0.5;
    private static final double MEDIUM_ATR_MULT = 1.0;
    private static final double COARSE_ATR_MULT = 2.0;

    // Default ZigZag params (matching ZigZagService defaults)
    private static final int    DEFAULT_ATR_LEN     = 14;
    private static final double DEFAULT_PCT_MIN      = 1.0;
    private static final double DEFAULT_HYST         = 0.3;
    private static final int    DEFAULT_MIN_BARS     = 2;
    private static final boolean DEFAULT_DYNAMIC_PCT = false;
    private static final double DEFAULT_VOL_MULT     = 1.5;
    private static final int    DEFAULT_RVOL_WINDOW  = 20;

    private final ZigZagService zigZagService;

    public MultiDegreePivotSet build(BarSeries barSeries, String symbol, String timeframe) {
        ZigZagParams fineParams = ZigZagParams.ofDefaults(
                DEFAULT_ATR_LEN, FINE_ATR_MULT, DEFAULT_PCT_MIN, DEFAULT_HYST,
                DEFAULT_MIN_BARS, DEFAULT_DYNAMIC_PCT, DEFAULT_VOL_MULT, DEFAULT_RVOL_WINDOW,
                ZigZagParams.Mode.LIVE);

        ZigZagParams mediumParams = ZigZagParams.ofDefaults(
                DEFAULT_ATR_LEN, MEDIUM_ATR_MULT, DEFAULT_PCT_MIN, DEFAULT_HYST,
                DEFAULT_MIN_BARS, DEFAULT_DYNAMIC_PCT, DEFAULT_VOL_MULT, DEFAULT_RVOL_WINDOW,
                ZigZagParams.Mode.LIVE);

        ZigZagParams coarseParams = ZigZagParams.ofDefaults(
                DEFAULT_ATR_LEN, COARSE_ATR_MULT, DEFAULT_PCT_MIN, DEFAULT_HYST,
                DEFAULT_MIN_BARS, DEFAULT_DYNAMIC_PCT, DEFAULT_VOL_MULT, DEFAULT_RVOL_WINDOW,
                ZigZagParams.Mode.LIVE);

        List<ZigZagPoint> fine   = zigZagService.detect(barSeries, fineParams);
        List<ZigZagPoint> medium = zigZagService.detect(barSeries, mediumParams);
        List<ZigZagPoint> coarse = zigZagService.detect(barSeries, coarseParams);

        return MultiDegreePivotSet.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .finePivots(fine)
                .mediumPivots(medium)
                .coarsePivots(coarse)
                .build();
    }
}
