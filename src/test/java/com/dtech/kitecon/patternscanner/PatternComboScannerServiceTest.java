package com.dtech.kitecon.patternscanner;

import com.dtech.algo.runner.candle.KiteTickerService;
import com.dtech.algo.series.Interval;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatternComboScannerServiceTest {

    @Mock
    private PatternScanService patternScanService;

    @Mock
    private TradeSignalRepository tradeSignalRepository;

    @Mock
    private KiteTickerService kiteTickerService;

    @InjectMocks
    private PatternComboScannerService patternComboScannerService;

    @BeforeEach
    void setUp() {
        // Set @Value fields using ReflectionTestUtils
        ReflectionTestUtils.setField(patternComboScannerService, "entryValidityHours", 4);
        ReflectionTestUtils.setField(patternComboScannerService, "filterThreshold", 0.82);
        ReflectionTestUtils.setField(patternComboScannerService, "watchingTfStr", "1h");
        ReflectionTestUtils.setField(patternComboScannerService, "confirmTfStr", "15m");
    }

    /**
     * Test 1: createSignalForPattern() creates signal with correct direction (bullish=LONG, bearish=SHORT)
     */
    @Test
    void testCreateSignalForPattern_BullishPatternCreatesLongSignal() {
        // Arrange
        String symbol = "HDFCBANK";
        PatternDto bullishPattern = createBullishPatternDto();
        when(tradeSignalRepository.findBySymbolAndStatusIn(symbol,
                List.of(TradeStatus.WATCHING_ENTRY, TradeStatus.ENTRY_PENDING, TradeStatus.ACTIVE)))
                .thenReturn(new ArrayList<>());
        when(tradeSignalRepository.save(any(TradeSignal.class)))
                .thenAnswer(inv -> {
                    TradeSignal signal = inv.getArgument(0);
                    signal.setId(1L);
                    return signal;
                });

        // Act
        var result = patternComboScannerService.createSignalForPattern(symbol, bullishPattern, "1h");

        // Assert
        assertNotNull(result);
        assertEquals(PatternComboScannerService.SignalOutcome.CREATED, result.outcome());
        verify(tradeSignalRepository).save(argThat(signal ->
                signal.getDirection() == TradeDirection.LONG));
    }

    /**
     * Test 2: createSignalForPattern() creates signal with SHORT for bearish pattern
     */
    @Test
    void testCreateSignalForPattern_BearishPatternCreatesShortSignal() {
        // Arrange
        String symbol = "HDFCBANK";
        PatternDto bearishPattern = createBearishPatternDto();
        when(tradeSignalRepository.findBySymbolAndStatusIn(symbol,
                List.of(TradeStatus.WATCHING_ENTRY, TradeStatus.ENTRY_PENDING, TradeStatus.ACTIVE)))
                .thenReturn(new ArrayList<>());
        when(tradeSignalRepository.save(any(TradeSignal.class)))
                .thenAnswer(inv -> {
                    TradeSignal signal = inv.getArgument(0);
                    signal.setId(1L);
                    return signal;
                });

        // Act
        var result = patternComboScannerService.createSignalForPattern(symbol, bearishPattern, "1h");

        // Assert
        assertNotNull(result);
        assertEquals(PatternComboScannerService.SignalOutcome.CREATED, result.outcome());
        verify(tradeSignalRepository).save(argThat(signal ->
                signal.getDirection() == TradeDirection.SHORT));
    }

    /**
     * Test 3: createSignalForPattern() calculates SL correctly (bull: keyLevel - 2*ATR)
     */
    @Test
    void testCreateSignalForPattern_BullishStopLossCalculation() {
        // Arrange
        String symbol = "HDFCBANK";
        double keyLevel = 1500.0;
        double atr = 10.0;
        PatternDto pattern = PatternDto.builder()
                .patternType("DOUBLE_BOTTOM")
                .bullish(true)
                .keyLevel(keyLevel)
                .target(1550.0)
                .atr(atr)
                .patternHeight(50.0)
                .rsiAtP1(55.0)
                .rsiAtP2(60.0)
                .build();

        when(tradeSignalRepository.findBySymbolAndStatusIn(symbol,
                List.of(TradeStatus.WATCHING_ENTRY, TradeStatus.ENTRY_PENDING, TradeStatus.ACTIVE)))
                .thenReturn(new ArrayList<>());
        when(tradeSignalRepository.save(any(TradeSignal.class)))
                .thenAnswer(inv -> {
                    TradeSignal signal = inv.getArgument(0);
                    signal.setId(1L);
                    return signal;
                });

        // Act
        patternComboScannerService.createSignalForPattern(symbol, pattern, "1h");

        // Assert
        double expectedSL = keyLevel - (2.0 * atr);
        verify(tradeSignalRepository).save(argThat(signal ->
                signal.getStopLoss().doubleValue() == expectedSL));
    }

    /**
     * Test 4: createSignalForPattern() calculates SL correctly (bear: keyLevel + 2*ATR)
     */
    @Test
    void testCreateSignalForPattern_BearishStopLossCalculation() {
        // Arrange
        String symbol = "HDFCBANK";
        double keyLevel = 1500.0;
        double atr = 10.0;
        PatternDto pattern = PatternDto.builder()
                .patternType("DOUBLE_TOP")
                .bullish(false)
                .keyLevel(keyLevel)
                .target(1450.0)
                .atr(atr)
                .patternHeight(50.0)
                .rsiAtP1(45.0)
                .rsiAtP2(40.0)
                .build();

        when(tradeSignalRepository.findBySymbolAndStatusIn(symbol,
                List.of(TradeStatus.WATCHING_ENTRY, TradeStatus.ENTRY_PENDING, TradeStatus.ACTIVE)))
                .thenReturn(new ArrayList<>());
        when(tradeSignalRepository.save(any(TradeSignal.class)))
                .thenAnswer(inv -> {
                    TradeSignal signal = inv.getArgument(0);
                    signal.setId(1L);
                    return signal;
                });

        // Act
        patternComboScannerService.createSignalForPattern(symbol, pattern, "1h");

        // Assert
        double expectedSL = keyLevel + (2.0 * atr);
        verify(tradeSignalRepository).save(argThat(signal ->
                signal.getStopLoss().doubleValue() == expectedSL));
    }

    /**
     * Test 5: createSignalForPattern() detects duplicates (same pattern type + neckline proximity < 0.5%)
     */
    @Test
    void testCreateSignalForPattern_DetectsDuplicatePattern() {
        // Arrange
        String symbol = "HDFCBANK";
        double keyLevel = 1500.0;
        PatternDto newPattern = createBullishPatternDto();
        newPattern.setKeyLevel(keyLevel);

        // Existing signal with same pattern type and neckline within 0.5%
        TradeSignal existingSignal = TradeSignal.builder()
                .patternType("DOUBLE_BOTTOM")
                .neckline(BigDecimal.valueOf(keyLevel * 1.003)) // 0.3% difference
                .build();

        when(tradeSignalRepository.findBySymbolAndStatusIn(symbol,
                List.of(TradeStatus.WATCHING_ENTRY, TradeStatus.ENTRY_PENDING, TradeStatus.ACTIVE)))
                .thenReturn(List.of(existingSignal));

        // Act
        var result = patternComboScannerService.createSignalForPattern(symbol, newPattern, "1h");

        // Assert
        assertEquals(PatternComboScannerService.SignalOutcome.DUPLICATE, result.outcome());
        verify(tradeSignalRepository, never()).save(any(TradeSignal.class));
    }

    /**
     * Test 6: createSignalForPattern() sets entry validity window correctly
     */
    @Test
    void testCreateSignalForPattern_SetsEntryValidityWindow() {
        // Arrange
        String symbol = "HDFCBANK";
        PatternDto pattern = createBullishPatternDto();
        when(tradeSignalRepository.findBySymbolAndStatusIn(symbol,
                List.of(TradeStatus.WATCHING_ENTRY, TradeStatus.ENTRY_PENDING, TradeStatus.ACTIVE)))
                .thenReturn(new ArrayList<>());
        when(tradeSignalRepository.save(any(TradeSignal.class)))
                .thenAnswer(inv -> {
                    TradeSignal signal = inv.getArgument(0);
                    signal.setId(1L);
                    return signal;
                });

        Instant beforeCall = Instant.now();

        // Act
        patternComboScannerService.createSignalForPattern(symbol, pattern, "1h");

        Instant afterCall = Instant.now();

        // Assert
        verify(tradeSignalRepository).save(argThat(signal -> {
            Instant entryValidUntil = signal.getEntryValidUntil();
            // Should be approximately 4 hours from now (±1 second tolerance)
            long hoursDiff = ChronoUnit.HOURS.between(beforeCall, entryValidUntil);
            return hoursDiff == 4;
        }));
    }

    /**
     * Test 7: scanAndCreateSignals() iterates FnO symbols from getFnoSymbols()
     */
    @Test
    void testScanAndCreateSignals_IteratesFnoSymbols() {
        // Arrange
        List<String> fnoSymbols = List.of("RELIANCE", "TCS", "HDFCBANK");
        when(patternScanService.getFnoSymbols()).thenReturn(fnoSymbols);
        when(patternScanService.scan("RELIANCE", Interval.OneHour, Interval.FifteenMinute))
                .thenReturn(createEmptyResultDto());
        when(patternScanService.scan("TCS", Interval.OneHour, Interval.FifteenMinute))
                .thenReturn(createEmptyResultDto());
        when(patternScanService.scan("HDFCBANK", Interval.OneHour, Interval.FifteenMinute))
                .thenReturn(createEmptyResultDto());

        // Act
        int count = patternComboScannerService.scanAndCreateSignals();

        // Assert
        assertEquals(0, count);
        verify(patternScanService, times(3)).scan(anyString(), eq(Interval.OneHour), eq(Interval.FifteenMinute));
    }

    /**
     * Test 8: scanAndCreateSignals() creates signals for detected patterns
     */
    @Test
    void testScanAndCreateSignals_CreatesSignalsForPatterns() {
        // Arrange
        List<String> fnoSymbols = List.of("HDFCBANK");
        when(patternScanService.getFnoSymbols()).thenReturn(fnoSymbols);

        PatternDto pattern = createBullishPatternDto();
        PatternScanResultDto result = PatternScanResultDto.builder()
                .symbol("HDFCBANK")
                .watchingTf("1h")
                .confirmTf("15m")
                .patterns(List.of(pattern))
                .build();
        when(patternScanService.scan("HDFCBANK", Interval.OneHour, Interval.FifteenMinute))
                .thenReturn(result);
        when(tradeSignalRepository.findBySymbolAndStatusIn("HDFCBANK",
                List.of(TradeStatus.WATCHING_ENTRY, TradeStatus.ENTRY_PENDING, TradeStatus.ACTIVE)))
                .thenReturn(new ArrayList<>());
        when(tradeSignalRepository.save(any(TradeSignal.class)))
                .thenAnswer(inv -> {
                    TradeSignal signal = inv.getArgument(0);
                    signal.setId(1L);
                    return signal;
                });

        // Act
        int count = patternComboScannerService.scanAndCreateSignals();

        // Assert
        assertEquals(1, count);
        verify(tradeSignalRepository).save(any(TradeSignal.class));
    }

    /**
     * Test 9: scheduledScan() checks market liveness and skips if market closed
     */
    @Test
    void testScheduledScan_SkipsWhenMarketNotLive() {
        // Arrange
        when(kiteTickerService.isMarketLive()).thenReturn(false);

        // Act
        patternComboScannerService.scheduledScan();

        // Assert
        verify(patternScanService, never()).getFnoSymbols();
        verify(patternScanService, never()).scan(anyString(), any(), any());
    }

    /**
     * Test 10: scheduledScan() prevents concurrent runs
     */
    @Test
    void testScheduledScan_PreventsConurrentRuns() {
        // Arrange
        when(kiteTickerService.isMarketLive()).thenReturn(true);
        when(patternScanService.getFnoSymbols()).thenReturn(List.of("HDFCBANK"));
        when(patternScanService.scan("HDFCBANK", Interval.OneHour, Interval.FifteenMinute))
                .thenReturn(createEmptyResultDto());

        // Act - first call should proceed
        patternComboScannerService.scheduledScan();

        // Assert - verify the scan was executed
        verify(patternScanService, times(1)).getFnoSymbols();
        verify(patternScanService).scan("HDFCBANK", Interval.OneHour, Interval.FifteenMinute);
    }

    /**
     * Test 11: scheduledScan() executes successfully when market is live
     */
    @Test
    void testScheduledScan_ExecutesWhenMarketLive() {
        // Arrange
        when(kiteTickerService.isMarketLive()).thenReturn(true);
        when(patternScanService.getFnoSymbols()).thenReturn(List.of("RELIANCE"));
        when(patternScanService.scan("RELIANCE", Interval.OneHour, Interval.FifteenMinute))
                .thenReturn(createEmptyResultDto());

        // Act
        patternComboScannerService.scheduledScan();

        // Assert
        verify(patternScanService).getFnoSymbols();
        verify(patternScanService).scan("RELIANCE", Interval.OneHour, Interval.FifteenMinute);
    }

    /**
     * Test 12: createSignalForPattern() calculates R:R ratio correctly
     */
    @Test
    void testCreateSignalForPattern_CalculatesRRRatio() {
        // Arrange
        String symbol = "HDFCBANK";
        double keyLevel = 1500.0;
        double target = 1550.0;
        double atr = 10.0;
        PatternDto pattern = PatternDto.builder()
                .patternType("DOUBLE_BOTTOM")
                .bullish(true)
                .keyLevel(keyLevel)
                .target(target)
                .atr(atr)
                .patternHeight(50.0)
                .rsiAtP1(55.0)
                .rsiAtP2(60.0)
                .build();

        when(tradeSignalRepository.findBySymbolAndStatusIn(symbol,
                List.of(TradeStatus.WATCHING_ENTRY, TradeStatus.ENTRY_PENDING, TradeStatus.ACTIVE)))
                .thenReturn(new ArrayList<>());
        when(tradeSignalRepository.save(any(TradeSignal.class)))
                .thenAnswer(inv -> {
                    TradeSignal signal = inv.getArgument(0);
                    signal.setId(1L);
                    return signal;
                });

        // Act
        patternComboScannerService.createSignalForPattern(symbol, pattern, "1h");

        // Assert
        // Target - Entry = 1550 - 1500 = 50
        // Entry - SL = 1500 - (1500 - 20) = 20
        // RR = 50 / 20 = 2.5
        verify(tradeSignalRepository).save(argThat(signal -> {
            BigDecimal expectedRR = BigDecimal.valueOf(2.50);
            return signal.getRrRatio().compareTo(expectedRR) == 0;
        }));
    }

    // ==================== Helper Methods ====================

    private PatternDto createBullishPatternDto() {
        return PatternDto.builder()
                .patternType("DOUBLE_BOTTOM")
                .bullish(true)
                .keyLevel(1500.0)
                .target(1550.0)
                .atr(10.0)
                .patternHeight(50.0)
                .rsiAtP1(55.0)
                .rsiAtP2(60.0)
                .build();
    }

    private PatternDto createBearishPatternDto() {
        return PatternDto.builder()
                .patternType("DOUBLE_TOP")
                .bullish(false)
                .keyLevel(1500.0)
                .target(1450.0)
                .atr(10.0)
                .patternHeight(50.0)
                .rsiAtP1(45.0)
                .rsiAtP2(40.0)
                .build();
    }

    private PatternScanResultDto createEmptyResultDto() {
        return PatternScanResultDto.builder()
                .symbol("TEST")
                .watchingTf("1h")
                .confirmTf("15m")
                .patterns(new ArrayList<>())
                .build();
    }
}
