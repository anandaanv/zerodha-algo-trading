package com.dtech.trade.instrument;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class InstrumentBarSeriesManagerTest {

    @Mock
    private Instrument instrument;
    @Mock
    private CandleRepository candleRepository;

    @InjectMocks
    private InstrumentBarSeriesManager instrumentBarSeriesManager;

//    @Test
//    void initialize() {
//        List<? extends Candle> singletonList = Arrays.asList(
//                new Candle(1.0, 1.0, 1.0, 1.0, 10L, 10L, LocalDateTime.now(), instrument, Interval.OneMinute),
//                new Candle(1.0, 1.0, 1.0, 1.0, 10L, 10L, LocalDateTime.now(), instrument, Interval.OneMinute));
//        Mockito.when(candleRepository.findAllByInstrument(instrument, Interval.OneMinute)).thenAnswer(invocation -> singletonList);
//        Mockito.when(candleRepository.findAllByInstrument(instrument, Interval.OneMinute)).thenReturn(new ArrayList<>());
//        Mockito.when(candleRepository.getAllIntervals()).thenReturn(Arrays.asList("1minute", "15minute"));
//        instrumentBarSeriesManager.initialize();
//        Mockito.verify(candleRepository, Mockito.times(1)).getAllIntervals();
//        Mockito.verify(candleRepository, Mockito.times(1)).findAllByInstrument("1minute", instrument);
//        Mockito.verify(candleRepository, Mockito.times(1)).findAllByInstrument("15minute", instrument);
//        assertEquals(instrumentBarSeriesManager.candleMap.get("1minute").size(), 2);
//        assertEquals(instrumentBarSeriesManager.barSeriesMap.get("1minute").getBarCount(), 2);
//    }
}