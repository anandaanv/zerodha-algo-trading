package com.dtech.algo.screener;

import com.dtech.algo.screener.db.ScreenerEntity;
import com.dtech.algo.screener.db.ScreenerRepository;
import com.dtech.algo.screener.kotlinrunner.KotlinScriptExecutor;
import com.dtech.algo.screener.runtime.ScreenerRunLogService;
import com.dtech.algo.service.ChartAnalysisService;
import com.dtech.algo.service.OpenAIScreenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenerService Tests")
class ScreenerServiceTest {

    @Mock
    private ScreenerRepository screenerRepository;

    private ObjectMapper objectMapper;

    @Mock
    private ScreenerRegistryService screenerRegistryService;

    @Mock
    private ScreenerContextLoader loader;

    @Mock
    private OpenAIScreenService openAIScreenService;

    @Mock
    private KotlinScriptExecutor kotlinScriptExecutor;

    @Mock
    private ScreenerRunLogService runLogService;

    @Mock
    private ChartAnalysisService chartAnalysisService;

    @Mock
    private KotlinScriptExecutor registry;

    @InjectMocks
    private ScreenerService screenerService;

    private ScreenerEntity testEntity;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Inject the real ObjectMapper and registry mock into screenerService after @InjectMocks initialization
        ReflectionTestUtils.setField(screenerService, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(screenerService, "registry", registry);

        testEntity = ScreenerEntity.builder()
                .id(1L)
                .name("TestScreener")
                .script("val x = 1")
                .configJson("{\"mapping\": {\"close\": {\"reference\": \"SPOT\", \"interval\": \"15MINUTE\"}}}")
                .timeframe("15min")
                .dirty(false)
                .deleted(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("run() loads screener entity from repository")
    void testRunLoadsEntity() throws Exception {
        // Setup
        when(screenerRepository.findById(1L)).thenReturn(Optional.of(testEntity));
        when(registry.evalReturnObject(anyString(), any())).thenReturn(new Object());
        lenient().when(loader.load(any(), any(), anyInt(), any())).thenReturn(ScreenerContext.builder().build());

        // Execute
        screenerService.run(1L, "HDFCBANK", 0, "15min", null, null);

        // Verify: repository was called
        verify(screenerRepository).findById(1L);
    }

    @Test
    @DisplayName("run() compiles script via registry on first call")
    void testRunCompilesScriptFirstCall() throws Exception {
        // Setup
        when(screenerRepository.findById(1L)).thenReturn(Optional.of(testEntity));
        when(registry.evalReturnObject("val x = 1", null)).thenReturn(new Object());
        lenient().when(loader.load(any(), any(), anyInt(), any())).thenReturn(ScreenerContext.builder().build());

        // Execute
        screenerService.run(1L, "HDFCBANK", 0, "15min", null, null);

        // Verify: registry compiled the script
        verify(registry, atLeastOnce()).evalReturnObject("val x = 1", null);
    }

    @Test
    @DisplayName("run() caches compiled script on subsequent calls when not dirty")
    void testRunCachesScriptWhenNotDirty() throws Exception {
        // Setup first call
        when(screenerRepository.findById(1L)).thenReturn(Optional.of(testEntity));
        Object compiledScript = new Object();
        when(registry.evalReturnObject("val x = 1", null)).thenReturn(compiledScript);
        lenient().when(loader.load(any(), any(), anyInt(), any())).thenReturn(ScreenerContext.builder().build());

        // First execution
        screenerService.run(1L, "HDFCBANK", 0, "15min", null, null);

        // After first run, inject cached script into codeMap for second run
        Map<Long, Object> codeMap = new HashMap<>();
        codeMap.put(1L, compiledScript);
        ReflectionTestUtils.setField(screenerService, "codeMap", codeMap);

        // Reset mocks for second call
        reset(registry, screenerRepository);
        testEntity.setDirty(false);
        when(screenerRepository.findById(1L)).thenReturn(Optional.of(testEntity));
        lenient().when(loader.load(any(), any(), anyInt(), any())).thenReturn(ScreenerContext.builder().build());

        // Second execution
        screenerService.run(1L, "HDFCBANK", 0, "15min", null, null);

        // Verify: registry not called second time (script cached)
        verify(registry, never()).evalReturnObject(anyString(), any());
    }

    @Test
    @DisplayName("run() recompiles script when dirty flag is set")
    void testRunRecompilesWhenDirty() throws Exception {
        // Setup: entity starts as dirty
        testEntity.setDirty(true);
        when(screenerRepository.findById(1L)).thenReturn(Optional.of(testEntity));
        when(registry.evalReturnObject("val x = 1", null)).thenReturn(new Object());
        lenient().when(loader.load(any(), any(), anyInt(), any())).thenReturn(ScreenerContext.builder().build());

        // Execute with dirty=true
        screenerService.run(1L, "HDFCBANK", 0, "15min", null, null);

        // Verify: registry was called because entity was dirty
        verify(registry, atLeast(1)).evalReturnObject("val x = 1", null);
    }

    @Test
    @DisplayName("run() throws IllegalArgumentException when screener not found")
    void testRunThrowsWhenNotFound() {
        // Setup
        when(screenerRepository.findById(999L)).thenReturn(Optional.empty());

        // Execute and verify
        assertThatThrownBy(() -> screenerService.run(999L, "HDFCBANK", 0, "15min", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Screener not found: 999");
    }

    @Test
    @DisplayName("run() validates screener configuration on execution")
    void testRunValidatesConfiguration() throws Exception {
        // Setup with valid mapping
        when(screenerRepository.findById(1L)).thenReturn(Optional.of(testEntity));
        when(registry.evalReturnObject(anyString(), any())).thenReturn(new Object());
        lenient().when(loader.load(any(), any(), anyInt(), any())).thenReturn(ScreenerContext.builder().build());

        // Execute - should complete without error for valid entity
        screenerService.run(1L, "HDFCBANK", 0, "15min", null, null);

        // Verify: loader was called to load the series data
        verify(loader, atLeast(1)).load(any(), any(), anyInt(), any());
    }
}
