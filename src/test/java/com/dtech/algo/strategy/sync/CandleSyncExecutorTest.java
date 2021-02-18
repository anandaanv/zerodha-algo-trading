package com.dtech.algo.strategy.sync;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.service.CandleFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ta4j.core.BaseBar;

@ExtendWith(MockitoExtension.class)
class CandleSyncExecutorTest {

    @Mock
    private BaseBar baseBar1;

    @Mock
    private CandleFacade candleFacade;

    @Mock
    private CandleRepository candleRepository;

    @InjectMocks
    private CandleSyncExecutor candleSyncExecutor;

    @Test
    void queueNewCandle() throws InterruptedException {
        Long instrument = 1L;
        CandleSyncJob syncJob = new CandleSyncJob(candleRepository, candleFacade, baseBar1, instrument.toString(), Interval.FifteenMinute);
        CandleSyncJob job = Mockito.spy(syncJob);
        candleSyncExecutor.initialize();
        candleSyncExecutor.submit(job);
        candleSyncExecutor.shutdown();
        Mockito.verify(job, Mockito.times(1)).insertNewCandle(instrument, baseBar1, Interval.FifteenMinute);
    }
}