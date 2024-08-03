package com.dtech.algo.strategy.sync;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.service.CandleFacade;
import lombok.RequiredArgsConstructor;
import org.ta4j.core.BaseBar;

import java.util.Collections;

@RequiredArgsConstructor
public class CandleSyncJob implements Runnable {

    private final CandleRepository candleRepository;
    private final CandleFacade candleFacade;

    private final CandleSyncToken syncToken;

    @Override
    public void run() {
        Long instrument = Long.valueOf(syncToken.getInstrument());
        insertNewCandle(instrument, syncToken.getBaseBar(), syncToken.getInterval());
    }

    protected void insertNewCandle(Long instrument, BaseBar baseBar, Interval interval) {
        Instrument ins = new Instrument();
        ins.setInstrumentToken(instrument);
        Candle candle = candleFacade.buildCandle(ins, baseBar, interval);
        candleRepository.saveAll(Collections.singletonList(candle));
    }
}
