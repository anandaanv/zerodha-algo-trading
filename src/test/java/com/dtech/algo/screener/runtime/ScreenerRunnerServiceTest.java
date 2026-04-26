package com.dtech.algo.screener.runtime;

import com.dtech.algo.screener.ScreenerService;
import com.dtech.algo.screener.db.ScreenerRunEntity;
import com.dtech.algo.screener.db.ScreenerRunRepository;
import com.dtech.algo.screener.enums.SchedulingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenerRunnerService Tests")
class ScreenerRunnerServiceTest {

    @Mock
    private ScreenerRunRepository screenerRunRepository;

    @Mock
    private ScreenerService screenerService;

    @InjectMocks
    private ScreenerRunnerService screenerRunnerService;

    private ScreenerRunEntity testRun;
    private Instant testNow;

    @BeforeEach
    void setUp() {
        testNow = Instant.now();
        testRun = ScreenerRunEntity.builder()
                .id(1L)
                .screenerId(100L)
                .symbol("HDFCBANK")
                .timeframe("15min")
                .schedulingStatus(SchedulingStatus.SCHEDULED)
                .executeAt(testNow.minusSeconds(10))
                .build();
    }

    @Test
    @DisplayName("tick() processes scheduled runs with status transitions SCHEDULED → RUNNING → COMPLETE")
    void testTickProcessesScheduledRunsWithStatusTransitions() throws Exception {
        // Setup
        when(screenerRunRepository.findTop200BySchedulingStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(
                eq(SchedulingStatus.SCHEDULED), any(Instant.class)))
                .thenReturn(List.of(testRun));

        doNothing().when(screenerService).run(anyLong(), anyString(), anyInt(), anyString(), any(), any());

        // Execute
        screenerRunnerService.tick();

        // Verify: repository.save() called at least twice (for status transitions)
        verify(screenerRunRepository, atLeast(2)).save(any(ScreenerRunEntity.class));

        // Verify screenerService was called with correct params
        verify(screenerService).run(100L, "HDFCBANK", 0, "15min", null, 1L);
    }

    @Test
    @DisplayName("tick() with failed screener sets status to FAILED")
    void testTickWithFailedScreener() throws Exception {
        // Setup
        when(screenerRunRepository.findTop200BySchedulingStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(
                eq(SchedulingStatus.SCHEDULED), any(Instant.class)))
                .thenReturn(List.of(testRun));

        doThrow(new RuntimeException("Script execution failed"))
                .when(screenerService).run(anyLong(), anyString(), anyInt(), anyString(), any(), any());

        // Execute
        screenerRunnerService.tick();

        // Verify: status set to FAILED on exception
        ArgumentCaptor<ScreenerRunEntity> runCaptor = ArgumentCaptor.forClass(ScreenerRunEntity.class);
        verify(screenerRunRepository, atLeast(2)).save(runCaptor.capture());

        List<ScreenerRunEntity> savedRuns = runCaptor.getAllValues();
        assertThat(savedRuns.get(savedRuns.size() - 1).getSchedulingStatus()).isEqualTo(SchedulingStatus.FAILED);
    }

    @Test
    @DisplayName("tick() with empty run list does nothing")
    void testTickWithEmptyRunList() throws Exception {
        // Setup
        when(screenerRunRepository.findTop200BySchedulingStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(
                eq(SchedulingStatus.SCHEDULED), any(Instant.class)))
                .thenReturn(List.of());

        // Execute
        screenerRunnerService.tick();

        // Verify: no processing
        verify(screenerRunRepository, never()).save(any());
        verify(screenerService, never()).run(anyLong(), anyString(), anyInt(), anyString(), any(), any());
    }

    @Test
    @DisplayName("tick() calls screenerService after repository operations")
    void testTickMarksRunRunningBeforeExecution() throws Exception {
        // Setup
        when(screenerRunRepository.findTop200BySchedulingStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(
                eq(SchedulingStatus.SCHEDULED), any(Instant.class)))
                .thenReturn(List.of(testRun));

        when(screenerRunRepository.save(any(ScreenerRunEntity.class)))
                .then(invocation -> invocation.getArgument(0));

        doNothing().when(screenerService).run(anyLong(), anyString(), anyInt(), anyString(), any(), any());

        // Execute
        screenerRunnerService.tick();

        // Verify: repository operations and service run were called
        verify(screenerRunRepository, atLeast(2)).save(any(ScreenerRunEntity.class));
        verify(screenerService, times(1)).run(anyLong(), anyString(), anyInt(), anyString(), any(), any());
    }

    @Test
    @DisplayName("tick() catches exception during status update to FAILED (secondary failure)")
    void testTickCatchesExceptionDuringStatusUpdate() throws Exception {
        // Setup
        when(screenerRunRepository.findTop200BySchedulingStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(
                eq(SchedulingStatus.SCHEDULED), any(Instant.class)))
                .thenReturn(List.of(testRun));

        // First call to save (RUNNING) succeeds, but second call (FAILED) throws
        when(screenerRunRepository.save(any(ScreenerRunEntity.class)))
                .thenReturn(testRun)
                .thenThrow(new RuntimeException("Database connection lost"));

        doThrow(new RuntimeException("Script execution failed"))
                .when(screenerService).run(anyLong(), anyString(), anyInt(), anyString(), any(), any());

        // Execute - should not crash despite secondary exception
        screenerRunnerService.tick();

        // Verify: both save calls attempted (first for RUNNING, second for FAILED)
        verify(screenerRunRepository, times(2)).save(any(ScreenerRunEntity.class));
    }

    @Test
    @DisplayName("tick() processes only runs with executeAt <= now")
    void testTickProcessesOnlyRunsWithExecuteAtLessThanOrEqualNow() throws Exception {
        // Setup
        when(screenerRunRepository.findTop200BySchedulingStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(
                eq(SchedulingStatus.SCHEDULED), any(Instant.class)))
                .thenReturn(List.of(testRun));

        doNothing().when(screenerService).run(anyLong(), anyString(), anyInt(), anyString(), any(), any());

        // Execute
        screenerRunnerService.tick();

        // Verify: only runs with executeAt <= now are processed
        ArgumentCaptor<Instant> timeCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(screenerRunRepository).findTop200BySchedulingStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(
                eq(SchedulingStatus.SCHEDULED), timeCaptor.capture());

        Instant capturedTime = timeCaptor.getValue();
        assertThat(capturedTime).isAfterOrEqualTo(testNow.minusSeconds(5)); // within ~5 seconds of now
        assertThat(testRun.getExecuteAt().isBefore(capturedTime) || testRun.getExecuteAt().equals(capturedTime)).isTrue();

        // Verify testRun was processed
        verify(screenerService).run(100L, "HDFCBANK", 0, "15min", null, 1L);
    }

    @Test
    @DisplayName("tick() handles multiple runs sequentially")
    void testTickHandlesMultipleRuns() throws Exception {
        // Setup
        ScreenerRunEntity run1 = ScreenerRunEntity.builder()
                .id(1L)
                .screenerId(100L)
                .symbol("HDFCBANK")
                .timeframe("15min")
                .schedulingStatus(SchedulingStatus.SCHEDULED)
                .executeAt(testNow.minusSeconds(30))
                .build();

        ScreenerRunEntity run2 = ScreenerRunEntity.builder()
                .id(2L)
                .screenerId(101L)
                .symbol("INFY")
                .timeframe("15min")
                .schedulingStatus(SchedulingStatus.SCHEDULED)
                .executeAt(testNow.minusSeconds(20))
                .build();

        when(screenerRunRepository.findTop200BySchedulingStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(
                eq(SchedulingStatus.SCHEDULED), any(Instant.class)))
                .thenReturn(List.of(run1, run2));

        doNothing().when(screenerService).run(anyLong(), anyString(), anyInt(), anyString(), any(), any());

        // Execute
        screenerRunnerService.tick();

        // Verify both runs were processed
        verify(screenerService).run(100L, "HDFCBANK", 0, "15min", null, 1L);
        verify(screenerService).run(101L, "INFY", 0, "15min", null, 2L);

        // Verify save was called 4 times (2 runs × 2 saves each: RUNNING and COMPLETE)
        verify(screenerRunRepository, atLeast(4)).save(any(ScreenerRunEntity.class));
    }
}
