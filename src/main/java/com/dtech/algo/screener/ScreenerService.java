package com.dtech.algo.screener;

import com.dtech.algo.controller.dto.ChartAnalysisRequest;
import com.dtech.algo.screener.db.Screener;
import com.dtech.algo.screener.db.ScreenerRepository;
import com.dtech.algo.screener.enums.WorkflowStep;
import com.dtech.algo.series.Interval;
import com.dtech.algo.service.ChartAnalysisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScreenerService {

    private final ScreenerRepository screenerRepository;
    private final ObjectMapper objectMapper;
    private final ScreenerRegistryService screenerRegistryService;
    private final ScreenerContextLoader loader;
    private final ChartAnalysisService chartAnalysisService;
    /**
     * Run the screener identified by ID for a given underlying symbol.
     *
     * @param screenerId database ID of screener
     * @param symbol base symbol to load (e.g., "NIFTY")
     * @param nowIndex evaluation index (usually series.endIndex)
     * @param timeframe optional override timeframe metadata for context (falls back to entity.timeframe)
     * @param callback callback receiver (final consumer after any UOWs)
     */
    public void run(long screenerId, String symbol, int nowIndex, @Nullable String timeframe, @Nullable SignalCallback callback) {
        Screener screener = screenerRepository.findById(screenerId)
                .orElseThrow(() -> new IllegalArgumentException("Screener not found: " + screenerId));

        // If dirty, compile/register script before execution and clear flag
        if (Boolean.TRUE.equals(screener.getDirty())) {
            screenerRegistryService.registerScript(screener.getId(), screener.getScript());
            screener.setDirty(false);
            screenerRepository.save(screener);
        }

        ScreenerConfig config = parseConfig(screener.getConfigJson());
        Map<String, ScreenerContextLoader.SeriesSpec> mapping = Optional.ofNullable(config.getMapping())
                .orElseThrow(() -> new IllegalArgumentException("Screener mapping is missing for id=" + screenerId));

        // Build context via loader
        String tf = timeframe != null && !timeframe.isBlank() ? timeframe : screener.getTimeframe();
        ScreenerContext baseCtx = loader.load(symbol, mapping, nowIndex, tf);

        // Attach the Screener entity to the context
        ScreenerContext ctx = baseCtx.toBuilder()
                .screener(screener)
                .build();

        // Build callback chain: ScreenerUOW -> (optional OpenAIUOW) -> external callback
        boolean hasOpenAI = Optional.ofNullable(config.getWorkflow())
                .orElse(List.of(WorkflowStep.SCRIPT))
                .contains(WorkflowStep.OPENAI);

        UnitOfWork next = null;
        if (hasOpenAI) {
            next = getOpenAIUOW(screener, next);
        }

        UnitOfWork head = new ScreenerUOW(screenerRegistryService, screenerId, next);

        head.run(ctx);
    }

    @NotNull
    private OpenAIUOW getOpenAIUOW(Screener screener, UnitOfWork next) {
        // promptId takes precedence over text promptJson
        String effectivePrompt = (screener.getPromptId() != null && !screener.getPromptId().isBlank())
                ? screener.getPromptId()
                : screener.getPromptJson();

        return new OpenAIUOW(effectivePrompt, next) {
            @Override
            public void run(ScreenerContext ctx) {
                try {
                    // chartsJson contains alias names to be sent to AI
                    String[] aliasArr = new ObjectMapper().readValue(screener.getChartsJson(), String[].class);
                    List<String> chartAliases = Arrays.stream(aliasArr).map(String::trim).filter(s -> !s.isBlank()).toList();

                    // If you still want to do chart analysis, integrate by resolving aliases to series here.
                    // For now, we just attach aliases to context params for downstream use.
                    Map<String, Object> params = new HashMap<>(ctx.getParams() == null ? Map.of() : ctx.getParams());
                    params.put("chartAliases", chartAliases);
                    ScreenerContext enriched = ctx.toBuilder().params(params).build();

                    // Proceed to next unit (OpenAI prompt handling, etc.)
                    super.run(enriched);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private ScreenerConfig parseConfig(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, ScreenerConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid screener config JSON: " + e.getMessage(), e);
        }
    }
}
