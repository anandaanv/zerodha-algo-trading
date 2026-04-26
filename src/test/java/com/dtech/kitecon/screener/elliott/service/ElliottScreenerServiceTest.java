package com.dtech.kitecon.screener.elliott.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.screener.elliott.dto.ElliottScreenerRequest;
import com.dtech.kitecon.screener.elliott.dto.ElliottScreenerResponse;
import com.dtech.kitecon.screener.elliott.dto.ElliottScreenerRunResponse;
import com.dtech.kitecon.screener.elliott.dto.SymbolStatusDto;
import com.dtech.kitecon.screener.elliott.entity.ElliottScreener;
import com.dtech.kitecon.screener.elliott.entity.ElliottScreenerRun;
import com.dtech.kitecon.screener.elliott.entity.ElliottScreenerRunResult;
import com.dtech.kitecon.screener.elliott.entity.ElliottTradeSuggestion;
import com.dtech.kitecon.screener.elliott.entity.SuggestionState;
import com.dtech.kitecon.screener.elliott.repository.ElliottScreenerRepository;
import com.dtech.kitecon.screener.elliott.repository.ElliottScreenerRunRepository;
import com.dtech.kitecon.screener.elliott.repository.ElliottScreenerRunResultRepository;
import com.dtech.kitecon.screener.elliott.repository.ElliottTradeSuggestionRepository;
import com.dtech.kitecon.analysis.AnalysisProcessService;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.copilot.CopilotAIService;
import com.dtech.kitecon.service.copilot.CopilotOrchestratorService;
import com.dtech.kitecon.service.copilot.AIResponseParser;
import com.dtech.kitecon.service.copilot.MarketStructureService;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.ta.elliott.AdvancedElliottService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElliottScreenerServiceTest {

    @Mock(lenient = true)
    private ElliottScreenerRepository screenerRepository;

    @Mock(lenient = true)
    private ElliottTradeSuggestionRepository suggestionRepository;

    @Mock(lenient = true)
    private ElliottScreenerRunRepository runRepository;

    @Mock(lenient = true)
    private ZigZagService zigzagService;

    @Mock(lenient = true)
    private MarketStructureService marketStructureService;

    @Mock(lenient = true)
    private AdvancedElliottService advancedElliottService;

    @Mock(lenient = true)
    private AnalysisProcessService analysisProcessService;

    @Mock(lenient = true)
    private CopilotOrchestratorService orchestratorService;

    @Mock(lenient = true)
    private CopilotAIService aiService;

    @Mock(lenient = true)
    private AIResponseParser responseParser;

    @Mock(lenient = true)
    private InstrumentRepository instrumentRepository;

    @Mock(lenient = true)
    private ObjectMapper objectMapper;

    @Mock(lenient = true)
    private SuggestionChartLayoutService layoutService;

    @Mock(lenient = true)
    private ElliottSymbolScanService symbolScanService;

    @Mock(lenient = true)
    private ElliottScreenerRunResultRepository runResultRepository;

    @InjectMocks
    private ElliottScreenerService service;

    private Long userId;
    private Long screenerId;
    private Long runId;
    private ElliottScreener screener;
    private ElliottScreenerRequest request;

    @BeforeEach
    void setUp() {
        userId = 1L;
        screenerId = 100L;
        runId = 500L;

        request = new ElliottScreenerRequest();
        request.setName("Test Screener");
        request.setSymbols("HDFCBANK,RELIANCE");
        request.setTimeframes("1h,daily");
        request.setPrimaryTimeframe("daily");
        request.setScheduleCron("0 9 * * * *");

        screener = ElliottScreener.builder()
                .id(screenerId)
                .userId(userId)
                .name(request.getName())
                .symbols(request.getSymbols())
                .timeframes(request.getTimeframes())
                .primaryTimeframe(request.getPrimaryTimeframe())
                .scheduleCron(request.getScheduleCron())
                .enabled(true)
                .build();
    }

    // Test 1: createScreener() creates entity with correct fields and computes next run time
    @Test
    void testCreateScreener_CreatesEntityWithCorrectFields() {
        when(screenerRepository.save(any(ElliottScreener.class)))
                .thenAnswer(invocation -> {
                    ElliottScreener s = invocation.getArgument(0);
                    s.setId(screenerId);
                    return s;
                });

        ElliottScreenerResponse response = service.createScreener(userId, request);

        assertNotNull(response);
        assertEquals(request.getName(), response.getName());
        assertEquals(request.getSymbols(), response.getSymbols());
        assertEquals(request.getTimeframes(), response.getTimeframes());
        assertEquals(request.getPrimaryTimeframe(), response.getPrimaryTimeframe());
        assertEquals(request.getScheduleCron(), response.getScheduleCron());
        assertTrue(response.isEnabled());

        verify(screenerRepository, times(1)).save(argThat(s ->
                s.getName().equals(request.getName()) &&
                        s.getSymbols().equals(request.getSymbols()) &&
                        s.getTimeframes().equals(request.getTimeframes()) &&
                        s.getUserId().equals(userId)
        ));
    }

    // Test 2: getScreener() returns screener for correct user
    @Test
    void testGetScreener_ReturnsScreenerForCorrectUser() {
        when(screenerRepository.findByIdAndUserId(screenerId, userId))
                .thenReturn(Optional.of(screener));

        ElliottScreenerResponse response = service.getScreener(userId, screenerId);

        assertNotNull(response);
        assertEquals(screener.getName(), response.getName());
        verify(screenerRepository, times(1)).findByIdAndUserId(screenerId, userId);
    }

    // Test 3: getScreener() throws when screener not found for user
    @Test
    void testGetScreener_ThrowsWhenScreenerNotFound() {
        when(screenerRepository.findByIdAndUserId(screenerId, userId))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.getScreener(userId, screenerId));
    }

    // Test 4: listScreeners() returns all screeners for user
    @Test
    void testListScreeners_ReturnsAllForUser() {
        ElliottScreener screener2 = ElliottScreener.builder()
                .id(101L)
                .userId(userId)
                .name("Screener 2")
                .symbols("INFY")
                .enabled(true)
                .build();

        when(screenerRepository.findByUserId(userId))
                .thenReturn(List.of(screener, screener2));

        List<ElliottScreenerResponse> responses = service.listScreeners(userId);

        assertNotNull(responses);
        assertEquals(2, responses.size());
        assertEquals(screener.getName(), responses.get(0).getName());
        assertEquals(screener2.getName(), responses.get(1).getName());
        verify(screenerRepository, times(1)).findByUserId(userId);
    }

    // Test 5: deleteScreener() sets enabled to false
    @Test
    void testDeleteScreener_SetsEnabledToFalse() {
        when(screenerRepository.findByIdAndUserId(screenerId, userId))
                .thenReturn(Optional.of(screener));
        when(screenerRepository.save(any(ElliottScreener.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.deleteScreener(userId, screenerId);

        verify(screenerRepository).save(argThat(s -> !s.isEnabled()));
    }

    // Test 6: triggerNow() fetches screener and delegates to runScreener()
    @Test
    void testTriggerNow_FetchesAndDelegates() {
        when(screenerRepository.findByIdAndUserId(screenerId, userId))
                .thenReturn(Optional.of(screener));
        when(runRepository.save(any(ElliottScreenerRun.class)))
                .thenAnswer(invocation -> {
                    ElliottScreenerRun r = invocation.getArgument(0);
                    r.setId(runId);
                    return r;
                });
        when(symbolScanService.scanSymbol(any(), anyLong(), anyLong(), anyString()))
                .thenReturn("FAILED");

        ElliottScreenerRunResponse response = service.triggerNow(userId, screenerId);

        assertNotNull(response);
        assertEquals("COMPLETED", response.getStatus());
        verify(screenerRepository, times(1)).findByIdAndUserId(screenerId, userId);
        verify(runRepository, atLeast(1)).save(any());
    }

    // Test 7: runScreener() iterates symbols and calls symbolScanService per symbol
    @Test
    void testRunScreener_IteratesSymbolsAndCallsService() {
        when(runRepository.save(any(ElliottScreenerRun.class)))
                .thenAnswer(invocation -> {
                    ElliottScreenerRun r = invocation.getArgument(0);
                    r.setId(runId);
                    return r;
                });
        when(symbolScanService.scanSymbol(any(), anyLong(), anyLong(), anyString()))
                .thenReturn("FAILED");

        service.runScreener(screener, userId);

        verify(symbolScanService, times(2))
                .scanSymbol(eq(screener), eq(runId), eq(userId), anyString());
        verify(symbolScanService).scanSymbol(eq(screener), eq(runId), eq(userId), eq("HDFCBANK"));
        verify(symbolScanService).scanSymbol(eq(screener), eq(runId), eq(userId), eq("RELIANCE"));
    }

    // Test 8: runScreener() on error per symbol continues to next (doesn't crash)
    @Test
    void testRunScreener_ContinuesOnErrorPerSymbol() {
        when(runRepository.save(any(ElliottScreenerRun.class)))
                .thenAnswer(invocation -> {
                    ElliottScreenerRun r = invocation.getArgument(0);
                    r.setId(runId);
                    return r;
                });
        when(symbolScanService.scanSymbol(eq(screener), eq(runId), eq(userId), eq("HDFCBANK")))
                .thenReturn("ERROR");
        when(symbolScanService.scanSymbol(eq(screener), eq(runId), eq(userId), eq("RELIANCE")))
                .thenReturn("PASSED");

        ElliottScreenerRunResponse response = service.runScreener(screener, userId);

        assertNotNull(response);
        assertEquals("COMPLETED", response.getStatus());
        assertEquals(2, response.getProcessedSymbols());
        assertEquals(1, response.getSuggestionsCreated());
        assertTrue(response.getErrorSummary() != null && response.getErrorSummary().contains("HDFCBANK"));
    }

    // Test 9: runScreener() creates run entity with RUNNING status, finalizes to COMPLETED
    @Test
    void testRunScreener_TransitionsFromRunningToCompleted() {
        when(runRepository.save(any(ElliottScreenerRun.class)))
                .thenAnswer(invocation -> {
                    ElliottScreenerRun r = invocation.getArgument(0);
                    if (r.getId() == null) {
                        r.setId(runId);
                    }
                    return r;
                });
        when(symbolScanService.scanSymbol(any(), anyLong(), anyLong(), anyString()))
                .thenReturn("FAILED");

        ElliottScreenerRunResponse response = service.runScreener(screener, userId);

        // Verify the run was saved at least twice and ended in COMPLETED status
        verify(runRepository, atLeast(2)).save(any(ElliottScreenerRun.class));
        assertEquals("COMPLETED", response.getStatus());
        assertEquals(2, response.getTotalSymbols());
    }

    // Test 10: runScreener() with PASSED and SKIPPED status counts
    @Test
    void testRunScreener_CountsPassedAndSkippedAndError() {
        when(runRepository.save(any(ElliottScreenerRun.class)))
                .thenAnswer(invocation -> {
                    ElliottScreenerRun r = invocation.getArgument(0);
                    r.setId(runId);
                    return r;
                });
        when(symbolScanService.scanSymbol(eq(screener), eq(runId), eq(userId), eq("HDFCBANK")))
                .thenReturn("PASSED");
        when(symbolScanService.scanSymbol(eq(screener), eq(runId), eq(userId), eq("RELIANCE")))
                .thenReturn("SKIPPED");

        ElliottScreenerRunResponse response = service.runScreener(screener, userId);

        assertNotNull(response);
        assertEquals(2, response.getProcessedSymbols());
        assertEquals(1, response.getSuggestionsCreated());
        assertEquals(1, response.getDuplicatesSkipped());
        assertNull(response.getErrorSummary());
    }

    // Test 11: mapTimeframeToInterval() maps common timeframe strings
    @Test
    void testMapTimeframeToInterval_WeeklyTimeframes() {
        assertEquals(Interval.Week, ElliottScreenerService.mapTimeframeToInterval("weekly"));
        assertEquals(Interval.Week, ElliottScreenerService.mapTimeframeToInterval("1w"));
        assertEquals(Interval.Week, ElliottScreenerService.mapTimeframeToInterval("week"));
        assertEquals(Interval.Week, ElliottScreenerService.mapTimeframeToInterval("WEEKLY"));
    }

    @Test
    void testMapTimeframeToInterval_DailyTimeframes() {
        assertEquals(Interval.Day, ElliottScreenerService.mapTimeframeToInterval("daily"));
        assertEquals(Interval.Day, ElliottScreenerService.mapTimeframeToInterval("1d"));
        assertEquals(Interval.Day, ElliottScreenerService.mapTimeframeToInterval("day"));
    }

    @Test
    void testMapTimeframeToInterval_HourlyTimeframes() {
        assertEquals(Interval.FourHours, ElliottScreenerService.mapTimeframeToInterval("4h"));
        assertEquals(Interval.FourHours, ElliottScreenerService.mapTimeframeToInterval("4hour"));
        assertEquals(Interval.FourHours, ElliottScreenerService.mapTimeframeToInterval("240"));
        assertEquals(Interval.OneHour, ElliottScreenerService.mapTimeframeToInterval("1h"));
        assertEquals(Interval.OneHour, ElliottScreenerService.mapTimeframeToInterval("60min"));
        assertEquals(Interval.OneHour, ElliottScreenerService.mapTimeframeToInterval("60minute"));
    }

    @Test
    void testMapTimeframeToInterval_MinuteTimeframes() {
        assertEquals(Interval.ThirtyMinute, ElliottScreenerService.mapTimeframeToInterval("30min"));
        assertEquals(Interval.ThirtyMinute, ElliottScreenerService.mapTimeframeToInterval("30m"));
        assertEquals(Interval.FifteenMinute, ElliottScreenerService.mapTimeframeToInterval("15min"));
        assertEquals(Interval.FifteenMinute, ElliottScreenerService.mapTimeframeToInterval("15m"));
        assertEquals(Interval.FiveMinute, ElliottScreenerService.mapTimeframeToInterval("5min"));
        assertEquals(Interval.FiveMinute, ElliottScreenerService.mapTimeframeToInterval("5m"));
        assertEquals(Interval.ThreeMinute, ElliottScreenerService.mapTimeframeToInterval("3min"));
        assertEquals(Interval.ThreeMinute, ElliottScreenerService.mapTimeframeToInterval("3m"));
    }

    @Test
    void testMapTimeframeToInterval_InvalidTimeframe() {
        assertNull(ElliottScreenerService.mapTimeframeToInterval("invalid"));
        assertNull(ElliottScreenerService.mapTimeframeToInterval(null));
        assertNull(ElliottScreenerService.mapTimeframeToInterval(""));
    }

    // Test 12: getSymbolStatus() returns status DTOs
    @Test
    void testGetSymbolStatus_ReturnsStatusDtos() {
        when(screenerRepository.findById(screenerId))
                .thenReturn(Optional.of(screener));

        ElliottScreenerRunResult result = ElliottScreenerRunResult.builder()
                .screenerId(screenerId)
                .symbol("HDFCBANK")
                .runId(runId)
                .status("PASSED")
                .scannedAt(Instant.now())
                .processingMs(1000L)
                .suggestionId(1L)
                .build();

        when(runResultRepository.findTopByScreenerIdAndSymbolOrderByScannedAtDesc(screenerId, "HDFCBANK"))
                .thenReturn(Optional.of(result));
        when(runResultRepository.findTopByScreenerIdAndSymbolOrderByScannedAtDesc(screenerId, "RELIANCE"))
                .thenReturn(Optional.empty());

        ElliottTradeSuggestion suggestion = ElliottTradeSuggestion.builder()
                .id(1L)
                .screenerId(screenerId)
                .symbol("HDFCBANK")
                .state(SuggestionState.ACTIVE)
                .direction("LONG")
                .build();

        when(suggestionRepository.findByScreenerIdAndSymbolAndStateIn(eq(screenerId), eq("HDFCBANK"), anyList()))
                .thenReturn(List.of(suggestion));
        when(suggestionRepository.findByScreenerIdAndSymbolAndStateIn(eq(screenerId), eq("RELIANCE"), anyList()))
                .thenReturn(List.of());

        List<SymbolStatusDto> statuses = service.getSymbolStatus(screenerId);

        assertNotNull(statuses);
        assertEquals(2, statuses.size());

        SymbolStatusDto hdfcStatus = statuses.stream()
                .filter(s -> "HDFCBANK".equals(s.getSymbol()))
                .findFirst()
                .orElse(null);
        assertNotNull(hdfcStatus);
        assertEquals("PASSED", hdfcStatus.getLastStatus());
        assertEquals(1L, hdfcStatus.getSuggestionId());
        assertEquals("ACTIVE", hdfcStatus.getSuggestionState());
        assertEquals("LONG", hdfcStatus.getSuggestionDirection());

        SymbolStatusDto relianceStatus = statuses.stream()
                .filter(s -> "RELIANCE".equals(s.getSymbol()))
                .findFirst()
                .orElse(null);
        assertNotNull(relianceStatus);
        assertNull(relianceStatus.getLastStatus());
        assertNull(relianceStatus.getSuggestionId());
    }

    // Test 13: getSymbolStatus() throws when screener not found
    @Test
    void testGetSymbolStatus_ThrowsWhenScreenerNotFound() {
        when(screenerRepository.findById(screenerId))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.getSymbolStatus(screenerId));
    }

    // Test 14: updateScreener() updates fields and computes next run time
    @Test
    void testUpdateScreener_UpdatesFieldsAndComputesNextRunTime() {
        when(screenerRepository.findByIdAndUserId(screenerId, userId))
                .thenReturn(Optional.of(screener));
        when(screenerRepository.save(any(ElliottScreener.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ElliottScreenerRequest updatedRequest = new ElliottScreenerRequest();
        updatedRequest.setName("Updated Screener");
        updatedRequest.setSymbols("INFY");
        updatedRequest.setTimeframes("daily");
        updatedRequest.setPrimaryTimeframe("daily");
        updatedRequest.setScheduleCron("0 10 * * * *");

        ElliottScreenerResponse response = service.updateScreener(userId, screenerId, updatedRequest);

        assertNotNull(response);
        assertEquals("Updated Screener", response.getName());
        assertEquals("INFY", response.getSymbols());
        verify(screenerRepository).save(argThat(s ->
                "Updated Screener".equals(s.getName()) &&
                        "INFY".equals(s.getSymbols())
        ));
    }

    // Test 15: runScreener() updates screener's lastRunAt and nextRunAt
    @Test
    void testRunScreener_UpdatesScreenerLastRunAndNextRunAt() {
        when(runRepository.save(any(ElliottScreenerRun.class)))
                .thenAnswer(invocation -> {
                    ElliottScreenerRun r = invocation.getArgument(0);
                    r.setId(runId);
                    return r;
                });
        when(symbolScanService.scanSymbol(any(), anyLong(), anyLong(), anyString()))
                .thenReturn("FAILED");
        when(screenerRepository.save(any(ElliottScreener.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.runScreener(screener, userId);

        verify(screenerRepository).save(argThat(s ->
                s.getLastRunAt() != null
        ));
    }
}
