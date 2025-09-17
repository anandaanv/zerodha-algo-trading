package com.dtech.algo.screener;

import com.dtech.algo.screener.db.Screener;
import com.dtech.algo.screener.db.ScreenerRepository;
import com.dtech.kitecon.controller.BarSeriesHelper;
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
    private final BarSeriesHelper barSeriesHelper;
    private final ScreenerRegistryService screenerRegistryService;

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
        var loader = new ScreenerContextLoader(barSeriesHelper, new ScreenerContextLoader.DefaultOptionSymbolResolver());
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
            next = new OpenAIUOW(screener.getPromptJson(), next);
        }

        UnitOfWork head = new ScreenerUOW(screenerRegistryService, screenerId, next);

        head.run(ctx);
    }

    private ScreenerConfig parseConfig(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, ScreenerConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid screener config JSON: " + e.getMessage(), e);
        }
    }
}
