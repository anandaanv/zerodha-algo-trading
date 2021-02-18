package com.dtech.algo.strategy.sync;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.BaseCandle;
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

    private final BaseBar baseBar;
    private final String instrument;
    private final Interval interval;

    @Override
    public void run() {
        Long instrument = Long.valueOf(this.instrument);
        insertNewCandle(instrument, this.baseBar, this.interval);
    }

    protected void insertNewCandle(Long instrument, BaseBar baseBar, Interval interval) {
        Instrument ins = new Instrument();
        ins.setInstrumentToken(instrument);
        BaseCandle baseCandle = candleFacade.buildCandle(ins, baseBar, interval);
        candleRepository.saveAll(interval.toString(), Collections.singletonList(baseCandle));
    }
}
