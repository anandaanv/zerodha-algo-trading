package com.dtech.algo.strategy.sync;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.service.CandleFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ta4j.core.BaseBar;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class CandleSyncJobTest {

    @Mock
    private BaseBar baseBar1;

    @Mock
    private CandleFacade candleFacade;

    @Mock
    private CandleRepository candleRepository;

    @BeforeEach
    public void setup() {
        Mockito.doNothing().when(candleRepository).saveAll(any(), any());
    }

    @Test
    void queueNewCandle() throws InterruptedException {
        Long instrument = 1L;
        CandleSyncToken syncToken = new CandleSyncToken(baseBar1, instrument.toString(), Interval.FifteenMinute);
        CandleSyncJob syncJob = new CandleSyncJob(candleRepository, candleFacade, syncToken);
        ExecutorService service = Executors.newFixedThreadPool(1);
        CandleSyncJob job = Mockito.spy(syncJob);
        job.run();
        Mockito.verify(job, Mockito.times(1)).insertNewCandle(instrument, baseBar1, Interval.FifteenMinute);
        Mockito.verify(candleRepository, Mockito.times(1)).saveAll(eq(Interval.FifteenMinute.toString()), Mockito.any());
    }
}

//Satyam Negi - 8859642930
//Suraj Prakash - 9954245119