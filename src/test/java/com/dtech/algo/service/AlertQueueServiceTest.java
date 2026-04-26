package com.dtech.algo.service;

import com.dtech.algo.controller.dto.ChartAnalysisRequest;
import com.dtech.algo.controller.dto.ChartAnalysisResponse;
import com.dtech.algo.series.Interval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertQueueServiceTest {

    @Mock
    private ChartAnalysisService chartAnalysisService;

    @InjectMocks
    private AlertQueueService alertQueueService;

    @BeforeEach
    void setUp() {
        // Clear all queues before each test
        alertQueueService.clearAllQueues();

        // Initialize @Value fields using ReflectionTestUtils
        ReflectionTestUtils.setField(alertQueueService, "schedulerEnabled", true);
        ReflectionTestUtils.setField(alertQueueService, "schedulerDelayMs", 5000L);
        ReflectionTestUtils.setField(alertQueueService, "schedulerTypesProp", "SWING_BUY,ASTA_BUY");
        ReflectionTestUtils.setField(alertQueueService, "whatsappTypesProp", "");

        // Initialize listening types
        alertQueueService.initAlertListeningTypes();
    }

    @Test
    void testAddAlert_Success() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("strategy", "test");

        boolean added = alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY,
                "HDFCBANK",
                "OneHour",
                1500.25,
                "Buy signal detected",
                metadata
        );

        assertTrue(added);
        assertEquals(1, alertQueueService.getQueueSize(AlertQueueService.AlertType.SWING_BUY));
        assertEquals(1, alertQueueService.getTotalAlertsCount(AlertQueueService.AlertType.SWING_BUY));
    }

    @Test
    void testAddAlert_DuplicateDetection() {
        Map<String, Object> metadata = new HashMap<>();

        boolean added1 = alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_SELL,
                "INFY",
                "FifteenMinute",
                3500.00,
                "Sell signal",
                metadata
        );

        boolean added2 = alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_SELL,
                "INFY",
                "FifteenMinute",
                3500.00,
                "Sell signal",
                metadata
        );

        assertTrue(added1);
        assertFalse(added2);
        assertEquals(1, alertQueueService.getQueueSize(AlertQueueService.AlertType.SWING_SELL));
    }

    @Test
    void testAddAlert_DifferentSymbolNotDuplicate() {
        Map<String, Object> metadata = new HashMap<>();

        boolean added1 = alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY,
                "RELIANCE",
                "OneHour",
                2000.00,
                "Buy",
                metadata
        );

        boolean added2 = alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY,
                "TCS",
                "OneHour",
                2000.00,
                "Buy",
                metadata
        );

        assertTrue(added1);
        assertTrue(added2);
        assertEquals(2, alertQueueService.getQueueSize(AlertQueueService.AlertType.SWING_BUY));
    }

    @Test
    void testProcessAlerts_ProcessesAndRemovesFromTracking() {
        Map<String, Object> metadata = new HashMap<>();
        alertQueueService.addAlert(
                AlertQueueService.AlertType.ASTA_BUY,
                "MARUTI",
                "Day",
                8500.00,
                "ASTA Buy",
                metadata
        );

        alertQueueService.processAlerts(AlertQueueService.AlertType.ASTA_BUY);

        assertEquals(0, alertQueueService.getQueueSize(AlertQueueService.AlertType.ASTA_BUY));
    }

    @Test
    void testGetQueueSize() {
        Map<String, Object> metadata = new HashMap<>();
        alertQueueService.addAlert(AlertQueueService.AlertType.ASTA_SELL, "WIPRO", "OneHour", 500.00, "Sell", metadata);
        alertQueueService.addAlert(AlertQueueService.AlertType.ASTA_SELL, "WIPRO", "FifteenMinute", 500.50, "Sell", metadata);

        int size = alertQueueService.getQueueSize(AlertQueueService.AlertType.ASTA_SELL);

        assertEquals(2, size);
    }

    @Test
    void testGetTotalAlertsCount() {
        Map<String, Object> metadata = new HashMap<>();
        alertQueueService.addAlert(AlertQueueService.AlertType.SWING_BUY, "INFY", "OneHour", 1800.00, "Buy", metadata);
        alertQueueService.addAlert(AlertQueueService.AlertType.SWING_BUY, "TCS", "Day", 3200.00, "Buy", metadata);

        int count = alertQueueService.getTotalAlertsCount(AlertQueueService.AlertType.SWING_BUY);

        assertEquals(2, count);
    }

    @Test
    void testClearQueue() {
        Map<String, Object> metadata = new HashMap<>();
        alertQueueService.addAlert(AlertQueueService.AlertType.BOLLINGER_BAND_CHALLENGED, "HDFC", "OneHour", 2500.00, "BB", metadata);
        alertQueueService.addAlert(AlertQueueService.AlertType.BOLLINGER_BAND_CHALLENGED, "HDFC", "Day", 2510.00, "BB", metadata);

        alertQueueService.clearQueue(AlertQueueService.AlertType.BOLLINGER_BAND_CHALLENGED);

        assertEquals(0, alertQueueService.getQueueSize(AlertQueueService.AlertType.BOLLINGER_BAND_CHALLENGED));
    }

    @Test
    void testGetAllStatistics() {
        Map<String, Object> metadata = new HashMap<>();
        alertQueueService.addAlert(AlertQueueService.AlertType.SWING_BUY, "SBIN", "OneHour", 600.00, "Buy", metadata);
        alertQueueService.addAlert(AlertQueueService.AlertType.ASTA_SELL, "SBIN", "Day", 600.50, "Sell", metadata);

        Map<AlertQueueService.AlertType, AlertQueueService.AlertStats> stats = alertQueueService.getAllStatistics();

        assertNotNull(stats);
        assertTrue(stats.containsKey(AlertQueueService.AlertType.SWING_BUY));
        assertTrue(stats.containsKey(AlertQueueService.AlertType.ASTA_SELL));
    }
}
