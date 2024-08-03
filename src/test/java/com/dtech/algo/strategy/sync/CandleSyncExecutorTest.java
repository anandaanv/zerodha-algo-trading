package com.dtech.algo.strategy.sync;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.service.CandleFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ta4j.core.BaseBar;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class CandleSyncExecutorTest {

    @Mock
    private BaseBar baseBar1;

    @Mock
    private CandleFacade candleFacade;

    @Mock
    private CandleRepository candleRepository;

    @InjectMocks
    @Spy
    private CandleSyncExecutor candleSyncExecutor;

    @Test
    void queueNewCandle() throws InterruptedException {
        Long instrument = 1L;
        CandleSyncToken syncToken = new CandleSyncToken(baseBar1, instrument.toString(), Interval.FifteenMinute);
        CandleSyncJob syncJob = new CandleSyncJob(candleRepository, candleFacade,
                syncToken);
        CandleSyncJob job = Mockito.spy(syncJob);
        Mockito.doReturn(job).when(candleSyncExecutor).getSyncJob(any());
        candleSyncExecutor.initialize();
        candleSyncExecutor.submit(syncToken);
        candleSyncExecutor.shutdown();
        Mockito.verify(job, Mockito.times(1)).insertNewCandle(eq(instrument), eq(baseBar1),
                eq(Interval.FifteenMinute));
    }
}