package com.dtech.algo.screener;

import com.dtech.algo.screener.db.ScreenerEntity;
import com.dtech.algo.screener.db.ScreenerRepository;
import com.dtech.algo.screener.domain.Screener;
import com.dtech.algo.screener.enums.WorkflowStep;
import com.dtech.algo.screener.kotlinrunner.KotlinScriptExecutor;
import com.dtech.algo.service.ChartAnalysisService;
import com.dtech.algo.service.OpenAIScreenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScreenerService {

    private final ScreenerRepository screenerRepository;
    private final ObjectMapper objectMapper;
    private final ScreenerRegistryService screenerRegistryService;
    private final ScreenerContextLoader loader;
    private final OpenAIScreenService openAIScreenService;
    private final KotlinScriptExecutor kotlinScriptExecutor;
    private final com.dtech.algo.screener.runtime.ScreenerRunLogService runLogService;
    private final ChartAnalysisService chartAnalysisService;

    /**
     * Run the screener identified by ID for a given underlying symbol.
     */
    public void run(long screenerId, String symbol, int nowIndex, @Nullable String timeframe, @Nullable SignalCallback callback, @Nullable Long screenerRunId) {
        // Load entity and convert to domain
        ScreenerEntity entity = screenerRepository.findById(screenerId)
                .orElseThrow(() -> new IllegalArgumentException("Screener not found: " + screenerId));
        entity.setDirty(false);
        screenerRepository.save(entity);
        Screener screener = Screener.fromEntity(entity, objectMapper);

        Map<String, ScreenerContextLoader.SeriesSpec> mapping = Optional.ofNullable(screener.getMapping())
                .orElseThrow(() -> new IllegalArgumentException("Screener mapping is missing for id=" + screenerId));

        // Build context via loader
        String tf = timeframe != null && !timeframe.isBlank() ? timeframe : screener.getTimeframe();
        ScreenerContext baseCtx = loader.load(symbol, mapping, nowIndex, tf);

        // Add runId into params so UOWs can persist logs
        var params = new java.util.HashMap<String, Object>(baseCtx.getParams() == null ? java.util.Map.of() : baseCtx.getParams());
        if (screenerRunId != null) {
            params.put("screenerRunId", screenerRunId);
        }

        // Attach domain screener to the context
        ScreenerContext ctx = baseCtx.toBuilder()
                .screener(screener)
                .params(params)
                .build();

        // Build callback chain flags via domain
        boolean hasOpenAI = Optional.ofNullable(screener.getWorkflow())
                .orElse(List.of(WorkflowStep.SCRIPT))
                .contains(WorkflowStep.OPENAI);

        UnitOfWork next = null;
        if (hasOpenAI) {
            next = getOpenAIUOW(screener, next);
        }

        // Wrap tail with logging-aware UOWs
        if (hasOpenAI && next instanceof OpenAIUOW openAIUOW) {
            // not reachable via instanceof since OpenAIUOW is not a bean hierarchy here, so we construct below
        }

        UnitOfWork tail = null;
        if (hasOpenAI) {
            tail = new OpenAIUOW(screener.getPromptJson(), null, runLogService, chartAnalysisService);
        }

        UnitOfWork chain = hasOpenAI ? tail : null;
        UnitOfWork head = new ScreenerUOW(kotlinScriptExecutor, entity.getScript(), chain, runLogService, objectMapper);
        head.run(ctx);
    }

    private OpenAIUOW getOpenAIUOW(Screener screener, UnitOfWork next) {
        String effectivePrompt = screener.getEffectivePrompt();
        return new OpenAIUOW(effectivePrompt, next, runLogService, chartAnalysisService) {
            @Override
            public ScreenerOutput run(ScreenerContext ctx) {
                // Domain already carries charts as aliases in screener.getCharts()
                // You can enrich ctx.params with chartAliases if needed by downstream
                ScreenerContext enriched = ctx;
                if (screener.getCharts() != null && !screener.getCharts().isEmpty()) {
                    var params = new java.util.HashMap<String, Object>(ctx.getParams() == null ? java.util.Map.of() : ctx.getParams());
                    params.put("chartAliases", screener.getCharts());
                    enriched = ctx.toBuilder().params(params).build();
                }
                super.run(enriched);
                return null;
            }
        };
    }
}
