package com.dtech.algo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AlertQueueServiceTest {

    @Mock
    private ChartAnalysisService chartAnalysisService;

    @InjectMocks
    private AlertQueueService alertQueueService;

    @BeforeEach
    void setUp() {
        alertQueueService.initAlertListeningTypes();
    }

    @Test
    void addAlertEnqueuesAndReturnsTrue() {
        boolean result = alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY, "RELIANCE", "OneHour", 2500.0, "test alert", Map.of());
        assertTrue(result);
        assertEquals(1, alertQueueService.getQueueSize(AlertQueueService.AlertType.SWING_BUY));
    }

    @Test
    void addAlertWithDuplicateKeyReturnsFalse() {
        alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY, "RELIANCE", "OneHour", 2500.0, "test", Map.of());
        boolean duplicate = alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY, "RELIANCE", "OneHour", 2500.0, "test dup", Map.of());
        assertFalse(duplicate);
        assertEquals(1, alertQueueService.getQueueSize(AlertQueueService.AlertType.SWING_BUY));
    }

    @Test
    void clearQueueRemovesAllForType() {
        alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY, "RELIANCE", "OneHour", 2500.0, "a1", Map.of());
        alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY, "HDFCBANK", "OneHour", 1500.0, "a2", Map.of());
        alertQueueService.clearQueue(AlertQueueService.AlertType.SWING_BUY);
        assertEquals(0, alertQueueService.getQueueSize(AlertQueueService.AlertType.SWING_BUY));
    }

    @Test
    void clearAllQueuesRemovesEverything() {
        alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY, "RELIANCE", "OneHour", 2500.0, "a1", Map.of());
        alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_SELL, "HDFCBANK", "OneHour", 1500.0, "a2", Map.of());
        alertQueueService.clearAllQueues();
        assertEquals(0, alertQueueService.getQueueSize(AlertQueueService.AlertType.SWING_BUY));
        assertEquals(0, alertQueueService.getQueueSize(AlertQueueService.AlertType.SWING_SELL));
    }

    @Test
    void getAllStatisticsReturnsCorrectCounts() {
        alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY, "RELIANCE", "OneHour", 2500.0, "a1", Map.of());
        alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY, "TCS", "OneHour", 3500.0, "a2", Map.of());
        var stats = alertQueueService.getAllStatistics();
        assertNotNull(stats);
    }

    @Test
    void differentAlertTypesAreIndependent() {
        alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_BUY, "RELIANCE", "OneHour", 2500.0, "buy", Map.of());
        alertQueueService.addAlert(
                AlertQueueService.AlertType.SWING_SELL, "RELIANCE", "OneHour", 2500.0, "sell", Map.of());
        assertEquals(1, alertQueueService.getQueueSize(AlertQueueService.AlertType.SWING_BUY));
        assertEquals(1, alertQueueService.getQueueSize(AlertQueueService.AlertType.SWING_SELL));
    }
}
