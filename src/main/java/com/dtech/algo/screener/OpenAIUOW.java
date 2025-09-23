package com.dtech.algo.screener;

import com.dtech.algo.controller.dto.OpenAIAnalysisRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public class OpenAIUOW implements UnitOfWork {

    private final String promptJson;
    private final com.dtech.algo.service.OpenAIScreenService openAIScreenService;
    private final UnitOfWork next;

    @Override
    public void run(ScreenerContext ctx) {
        // Invoke OpenAI using request data from context/screener (no hardcoded prompt)
        try {
            var screener = ctx.getScreener();
            String symbol = ctx.getSymbol();

            String promptId = screener != null ? screener.getPromptId() : null;
            String promptJsonLocal = screener != null ? screener.getPromptJson() : promptJson;

            OpenAIAnalysisRequest request =
                    com.dtech.algo.controller.dto.OpenAIAnalysisRequest.builder()
                            .symbol(symbol)
                            .mapping(screener != null ? screener.getMapping() : null)
                            .promptId(promptId)
                            .promptJson(promptJsonLocal)
                            .build();

            com.dtech.algo.controller.dto.ChartAnalysisResponse response = openAIScreenService.analyze(request);

            // Attach results to context params for downstream steps
            var params = new java.util.HashMap<>(ctx.getParams() == null ? java.util.Map.of() : ctx.getParams());
            params.put("openAiAnalysis", response.getAnalysis());
            params.put("openAiJsonAnalysis", response.getJsonAnalysis());
            ScreenerContext updated = ctx.toBuilder().params(params).build();

            log.debug("OpenAIUOW.run screenerId={}, symbol={}, analysisLen={}",
                    screener != null ? screener.getId() : null,
                    symbol,
                    response.getAnalysis() != null ? response.getAnalysis().length() : 0);

            if (next != null) {
                next.run(updated);
            }
        } catch (Exception e) {
            log.error("OpenAIUOW.run error: {}", e.getMessage(), e);
            if (next != null) {
                next.run(ctx);
            }
        }
    }

    @Override
    public UnitOfWork next() {
        return next;
    }

    @Override
    public void onEntry(ScreenerContext ctx, String... tags) {
        // TODO: enrich with LLM-based tagging or rationale
        log.debug("OpenAIUOW.onEntry tags={}", (Object) tags);
    }

    @Override
    public void onExit(ScreenerContext ctx, String... tags) {
        log.debug("OpenAIUOW.onExit tags={}", (Object) tags);
    }

    @Override
    public void onEvent(String type, ScreenerContext ctx, Map<String, Object> meta) {
        log.debug("OpenAIUOW.onEvent type={}, meta={}", type, meta);
    }

    @Override
    public void match(String type, ScreenerContext ctx, Map<String, Object> meta) {
        // reserved for future matching logic
        log.debug("OpenAIUOW.match type={}, meta={}", type, meta);
    }
}
