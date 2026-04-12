package com.dtech.kitecon.patternscanner;

import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatternComboScannerServiceTest {

    @Mock
    private PatternScanService patternScanService;

    @Mock
    private TradeSignalRepository tradeSignalRepository;

    @InjectMocks
    private PatternComboScannerService patternComboScannerService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(patternComboScannerService, "entryValidityHours", 4);
        ReflectionTestUtils.setField(patternComboScannerService, "watchingTfStr", "1h");
        ReflectionTestUtils.setField(patternComboScannerService, "confirmTfStr", "15m");
    }

    @Test
    void testScanAndCreateSignals_CreateNewSignals() {
        PatternDto pattern = PatternDto.builder()
                .patternType("DOUBLE_BOTTOM")
                .bullish(true)
                .keyLevel(2500.0)
                .target(2600.0)
                .patternHeight(100.0)
                .atr(20.0)
                .rsiAtP1(35.0)
                .rsiAtP2(40.0)
                .build();

        PatternScanResultDto result = PatternScanResultDto.builder()
                .symbol("RELIANCE")
                .watchingTf("1h")
                .patterns(Arrays.asList(pattern))
                .build();

        when(patternScanService.getNifty50()).thenReturn(Arrays.asList("RELIANCE", "TCS"));
        when(patternScanService.scan(any(), any(), any())).thenReturn(result);
        when(tradeSignalRepository.findBySymbolAndStatusIn(any(), anyList()))
                .thenReturn(Collections.emptyList());
        when(tradeSignalRepository.save(any())).thenAnswer(inv -> {
            TradeSignal signal = inv.getArgument(0);
            signal.setId(1L);
            return signal;
        });

        int created = patternComboScannerService.scanAndCreateSignals();

        assertEquals(2, created);
        verify(tradeSignalRepository, times(2)).save(any());
    }

    @Test
    void testScanAndCreateSignals_DuplicateDetection() {
        PatternDto pattern = PatternDto.builder()
                .patternType("DOUBLE_BOTTOM")
                .bullish(true)
                .keyLevel(2500.0)
                .target(2600.0)
                .patternHeight(100.0)
                .atr(20.0)
                .rsiAtP1(35.0)
                .rsiAtP2(40.0)
                .build();

        PatternScanResultDto result = PatternScanResultDto.builder()
                .symbol("RELIANCE")
                .watchingTf("1h")
                .patterns(Arrays.asList(pattern))
                .build();

        TradeSignal existingSignal = TradeSignal.builder()
                .id(1L)
                .symbol("RELIANCE")
                .patternType("DOUBLE_BOTTOM")
                .neckline(BigDecimal.valueOf(2501.0))
                .status(TradeStatus.WATCHING_ENTRY)
                .build();

        when(patternScanService.getNifty50()).thenReturn(Arrays.asList("RELIANCE"));
        when(patternScanService.scan(any(), any(), any())).thenReturn(result);
        when(tradeSignalRepository.findBySymbolAndStatusIn(any(), anyList()))
                .thenReturn(Arrays.asList(existingSignal));

        int created = patternComboScannerService.scanAndCreateSignals();

        assertEquals(0, created);
        verify(tradeSignalRepository, never()).save(any());
    }

    @Test
    void testScanAndCreateSignals_SignalStructure() {
        PatternDto pattern = PatternDto.builder()
                .patternType("DOUBLE_BOTTOM")
                .bullish(true)
                .keyLevel(2500.0)
                .target(2600.0)
                .patternHeight(100.0)
                .atr(20.0)
                .rsiAtP1(35.0)
                .rsiAtP2(40.0)
                .build();

        PatternScanResultDto result = PatternScanResultDto.builder()
                .symbol("RELIANCE")
                .watchingTf("1h")
                .patterns(Arrays.asList(pattern))
                .build();

        when(patternScanService.getNifty50()).thenReturn(Arrays.asList("RELIANCE"));
        when(patternScanService.scan(any(), any(), any())).thenReturn(result);
        when(tradeSignalRepository.findBySymbolAndStatusIn(any(), anyList()))
                .thenReturn(Collections.emptyList());
        when(tradeSignalRepository.save(any())).thenAnswer(inv -> {
            TradeSignal signal = inv.getArgument(0);
            signal.setId(1L);
            return signal;
        });

        patternComboScannerService.scanAndCreateSignals();

        ArgumentCaptor<TradeSignal> captor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(tradeSignalRepository).save(captor.capture());

        TradeSignal saved = captor.getValue();
        assertEquals("RELIANCE", saved.getSymbol());
        assertEquals(TradeDirection.LONG, saved.getDirection());
        assertEquals("DOUBLE_BOTTOM", saved.getPatternType());
        assertEquals(TradeStatus.WATCHING_ENTRY, saved.getStatus());
        assertEquals("1h", saved.getTimeframe());
        assertNotNull(saved.getEntryValidUntil());
        assertTrue(saved.getEntryValidUntil().isAfter(Instant.now()));
    }

    @Test
    void testScanAndCreateSignals_ErrorHandling() {
        when(patternScanService.getNifty50()).thenReturn(Arrays.asList("RELIANCE", "TCS"));
        when(patternScanService.scan(eq("RELIANCE"), any(), any()))
                .thenThrow(new RuntimeException("Scan failed"));
        when(patternScanService.scan(eq("TCS"), any(), any()))
                .thenReturn(PatternScanResultDto.builder().symbol("TCS").patterns(Collections.emptyList()).build());

        int created = patternComboScannerService.scanAndCreateSignals();

        assertEquals(0, created);
        verify(tradeSignalRepository, never()).save(any());
    }
}
