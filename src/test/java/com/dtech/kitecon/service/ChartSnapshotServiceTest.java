package com.dtech.kitecon.service;

import com.dtech.chartdata.service.ChartDataService;
import com.dtech.kitecon.data.ChartSnapshot;
import com.dtech.kitecon.data.UserSubscriptionPlan;
import com.dtech.kitecon.repository.ChartSnapshotRepository;
import com.dtech.kitecon.repository.UserSubscriptionPlanRepository;
import com.dtech.kitecon.service.ai.tools.ValidationResult;
import com.dtech.kitecon.service.model.SnapshotRequest;
import com.dtech.kitecon.service.model.SnapshotResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChartSnapshotServiceTest {

    @Mock
    private ChartSnapshotRepository snapshotRepository;

    @Mock
    private UserSubscriptionPlanRepository subscriptionRepository;

    @Mock
    private AIValidationOrchestrator validationOrchestrator;

    @Mock
    private DrawingExtractorService drawingExtractor;

    @Mock
    private TagValidationService tagValidationService;

    @Mock
    private ChartDataService chartDataService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ChartSnapshotService snapshotService;

    private com.fasterxml.jackson.databind.JsonNode mockJsonNode;

    @BeforeEach
    void setUp() throws Exception {
        mockJsonNode = mock(com.fasterxml.jackson.databind.JsonNode.class);
        com.fasterxml.jackson.databind.JsonNode drawingsNode = mock(com.fasterxml.jackson.databind.JsonNode.class);
        
        when(mockJsonNode.has("drawings")).thenReturn(true);
        when(mockJsonNode.get("drawings")).thenReturn(drawingsNode);
        when(drawingsNode.isArray()).thenReturn(true);
        when(drawingsNode.size()).thenReturn(1);
    }

    @Test
    void testCreateSnapshot_Success() throws Exception {
        String testChartStateJson = "{\"drawings\": [{\"type\": \"trendline\"}]}";
        SnapshotRequest request = SnapshotRequest.builder()
                .symbol("HDFCBANK")
                .timeframe("OneHour")
                .chartStateJson(testChartStateJson)
                .userComment("Test pattern")
                .patternType("TRIANGLE")
                .patternTags(List.of("bullish"))
                .visibility(ChartSnapshot.SnapshotVisibility.PRIVATE)
                .build();

        UserSubscriptionPlan plan = UserSubscriptionPlan.builder()
                .username("testuser")
                .planType(UserSubscriptionPlan.PlanType.PREMIUM)
                .privateSnapshotsLimit(100)
                .validUntil(LocalDateTime.now().plusDays(30))
                .build();

        ChartSnapshot savedSnapshot = ChartSnapshot.builder()
                .id(1L)
                .username("testuser")
                .symbol("HDFCBANK")
                .visibility(ChartSnapshot.SnapshotVisibility.PRIVATE)
                .build();

        when(objectMapper.readTree(testChartStateJson)).thenReturn(mockJsonNode);
        when(subscriptionRepository.findByUsername("testuser")).thenReturn(Optional.of(plan));
        when(snapshotRepository.countByUsernameAndVisibility("testuser", ChartSnapshot.SnapshotVisibility.PRIVATE)).thenReturn(0L);
        when(tagValidationService.validateAndNormalizeTags(any())).thenReturn(List.of("bullish"));
        when(drawingExtractor.extractDrawings(anyString())).thenReturn(List.of());
        when(chartDataService.getBars(anyString(), anyString(), any(), any(), anyBoolean())).thenReturn(List.of());
        when(validationOrchestrator.validatePattern(any())).thenReturn(
                ValidationResult.builder().isValid(true).confidence(0.85).build()
        );
        when(snapshotRepository.save(any())).thenReturn(savedSnapshot);

        SnapshotResult result = snapshotService.createSnapshot(request, "testuser");

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(1L, result.getSnapshotId());
    }

    @Test
    void testCreateSnapshot_NoDrawings() throws Exception {
        String testChartStateJson = "{\"drawings\": []}";
        SnapshotRequest request = SnapshotRequest.builder()
                .symbol("INFY")
                .timeframe("Day")
                .chartStateJson(testChartStateJson)
                .visibility(ChartSnapshot.SnapshotVisibility.PRIVATE)
                .build();

        com.fasterxml.jackson.databind.JsonNode emptyJsonNode = mock(com.fasterxml.jackson.databind.JsonNode.class);
        com.fasterxml.jackson.databind.JsonNode emptyDrawings = mock(com.fasterxml.jackson.databind.JsonNode.class);
        
        when(emptyJsonNode.has("drawings")).thenReturn(true);
        when(emptyJsonNode.get("drawings")).thenReturn(emptyDrawings);
        when(emptyDrawings.isArray()).thenReturn(true);
        when(emptyDrawings.size()).thenReturn(0);

        UserSubscriptionPlan plan = UserSubscriptionPlan.builder()
                .username("testuser")
                .planType(UserSubscriptionPlan.PlanType.FREE)
                .privateSnapshotsLimit(10)
                .build();

        when(objectMapper.readTree(testChartStateJson)).thenReturn(emptyJsonNode);
        when(subscriptionRepository.findByUsername("testuser")).thenReturn(Optional.of(plan));

        SnapshotResult result = snapshotService.createSnapshot(request, "testuser");

        assertNotNull(result);
        assertFalse(result.isSuccess());
    }

    @Test
    void testCreateSnapshot_SubscriptionLimitReached() throws Exception {
        String testChartStateJson = "{\"drawings\": [{\"type\": \"trendline\"}]}";
        SnapshotRequest request = SnapshotRequest.builder()
                .symbol("TCS")
                .timeframe("FifteenMinute")
                .chartStateJson(testChartStateJson)
                .visibility(ChartSnapshot.SnapshotVisibility.PRIVATE)
                .build();

        UserSubscriptionPlan plan = UserSubscriptionPlan.builder()
                .username("testuser")
                .planType(UserSubscriptionPlan.PlanType.FREE)
                .privateSnapshotsLimit(5)
                .build();

        when(objectMapper.readTree(testChartStateJson)).thenReturn(mockJsonNode);
        when(subscriptionRepository.findByUsername("testuser")).thenReturn(Optional.of(plan));
        when(snapshotRepository.countByUsernameAndVisibility("testuser", ChartSnapshot.SnapshotVisibility.PRIVATE)).thenReturn(5L);

        SnapshotResult result = snapshotService.createSnapshot(request, "testuser");

        assertNotNull(result);
        assertFalse(result.isSuccess());
    }

    @Test
    void testGetSnapshot_PublicVisibility() {
        ChartSnapshot snapshot = ChartSnapshot.builder()
                .id(1L)
                .username("user1")
                .symbol("RELIANCE")
                .visibility(ChartSnapshot.SnapshotVisibility.PUBLIC)
                .viewsCount(5)
                .build();

        when(snapshotRepository.findById(1L)).thenReturn(Optional.of(snapshot));
        when(snapshotRepository.save(any())).thenReturn(snapshot);

        ChartSnapshot result = snapshotService.getSnapshot(1L, "anyuser");

        assertNotNull(result);
        assertEquals("RELIANCE", result.getSymbol());
    }

    @Test
    void testGetSnapshot_PrivateOwnerCanView() {
        ChartSnapshot snapshot = ChartSnapshot.builder()
                .id(2L)
                .username("testuser")
                .symbol("WIPRO")
                .visibility(ChartSnapshot.SnapshotVisibility.PRIVATE)
                .build();

        when(snapshotRepository.findById(2L)).thenReturn(Optional.of(snapshot));

        ChartSnapshot result = snapshotService.getSnapshot(2L, "testuser");

        assertNotNull(result);
        assertEquals("WIPRO", result.getSymbol());
    }

    @Test
    void testDeleteSnapshot_Success() {
        ChartSnapshot snapshot = ChartSnapshot.builder()
                .id(1L)
                .username("testuser")
                .symbol("HDFCBANK")
                .build();

        when(snapshotRepository.findByIdAndUsername(1L, "testuser")).thenReturn(Optional.of(snapshot));

        boolean result = snapshotService.deleteSnapshot(1L, "testuser");

        assertTrue(result);
        verify(snapshotRepository).delete(any());
    }

    @Test
    void testDeleteSnapshot_NoPermission() {
        when(snapshotRepository.findByIdAndUsername(1L, "otheruser")).thenReturn(Optional.empty());

        boolean result = snapshotService.deleteSnapshot(1L, "otheruser");

        assertFalse(result);
    }

    @Test
    void testUpdateVisibility_Success() {
        ChartSnapshot snapshot = ChartSnapshot.builder()
                .id(1L)
                .username("testuser")
                .symbol("INFY")
                .visibility(ChartSnapshot.SnapshotVisibility.PRIVATE)
                .build();

        when(snapshotRepository.findByIdAndUsername(1L, "testuser")).thenReturn(Optional.of(snapshot));
        when(snapshotRepository.save(any())).thenReturn(snapshot);

        boolean result = snapshotService.updateVisibility(1L, "testuser",
                ChartSnapshot.SnapshotVisibility.PUBLIC, null);

        assertTrue(result);
    }

    @Test
    void testValidateOnly() throws Exception {
        String testChartStateJson = "{\"drawings\": [{\"type\": \"trendline\"}]}";
        SnapshotRequest request = SnapshotRequest.builder()
                .symbol("MARUTI")
                .timeframe("OneHour")
                .chartStateJson(testChartStateJson)
                .userComment("Bullish triangle")
                .build();

        when(objectMapper.readTree(testChartStateJson)).thenReturn(mockJsonNode);
        when(drawingExtractor.extractDrawings(anyString())).thenReturn(List.of());
        when(chartDataService.getBars(anyString(), anyString(), any(), any(), anyBoolean())).thenReturn(List.of());
        when(validationOrchestrator.validatePattern(any())).thenReturn(
                ValidationResult.builder().isValid(true).confidence(0.9).build()
        );

        ValidationResult result = snapshotService.validateOnly(request);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertEquals(0.9, result.getConfidence());
    }
}
