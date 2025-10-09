package com.dtech.algo.screener;

import com.dtech.algo.controller.dto.ChartAnalysisRequest;
import com.dtech.algo.controller.dto.ChartAnalysisResponse;
import com.dtech.algo.screener.dto.OpenAiTradeOutput;
import com.dtech.algo.screener.enums.Verdict;
import com.dtech.algo.screener.enums.WorkflowStep;
import com.dtech.algo.screener.runtime.ScreenerRunLogService;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.service.ChartAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
public class OpenAIUOW implements UnitOfWork {

    private final String promptJson;
    private final UnitOfWork next;
    private final ScreenerRunLogService runLogService;
    private final ChartAnalysisService chartAnalysisService;

    @Override
    public ScreenerOutput run(ScreenerContext ctx) {
        // Invoke OpenAI using request data from context/screener (no hardcoded prompt)
        try {
            Verdict verdict = Verdict.WAIT;
            Object ob = ctx.getParam("lastScreenerOutput");
            if (ob instanceof ScreenerOutput so) {
                verdict = so.getFinalVerdict();
            }
            var screener = ctx.getScreener();
            String symbol = ctx.getSymbol();

            String promptId = screener != null ? screener.getPromptId() : null;
            String promptJsonLocal = screener != null ? screener.getPromptJson() : promptJson;

            String prompt = promptId != null && !promptId.isBlank() ? promptId : promptJsonLocal;

            ChartAnalysisRequest chartAnalysisRequest =
                    ChartAnalysisRequest.builder()
                            .symbol(symbol)
                            .verdict(verdict)
                            .timeframes(ctx.getAliases().values().stream().map(
                                    IntervalBarSeries::getInterval
                            ).toList())
                            .intervalsMapping(ctx.getAliases().entrySet()
                                    .stream().collect(Collectors.toMap(
                                            Map.Entry::getKey, e -> e.getValue().getInterval()
                                    )))
                            .primaryInterval(ctx.getTimeframe())
                            .prompt(prompt)
                            .build();
            ChartAnalysisResponse response = chartAnalysisService.analyzeCharts(chartAnalysisRequest);

            // Log OPENAI step
            Long runId = getRunId(ctx);
            runLogService.logStep(runId, WorkflowStep.OPENAI, chartAnalysisRequest, response, true, null);

            // Attach results to context params for downstream steps (keep it minimal)
            Verdict updatedVerdict = verdict;
            var params = new java.util.HashMap<>(ctx.getParams() == null ? java.util.Map.of() : ctx.getParams());
            params.put("openAiAnalysis", response.getAnalysis());
            params.put("openAiJsonAnalysis", response.getJsonAnalysis());

            OpenAiTradeOutput ai;
            try {
                var om = new ObjectMapper();
                ai = om.convertValue(response.getJsonAnalysis(), OpenAiTradeOutput.class);
            } catch (Exception e) {
                ai = OpenAiTradeOutput.builder().symbol(symbol).build();
            }
            if (ai.getSymbol() == null || ai.getSymbol().isBlank()) ai.setSymbol(symbol);

            String translatedSummary = ai.getTranslatedSummary();

            if (ai.isHighConviction()) {
                Verdict aiVerdict = ai.getFinalVerdict();
                if (aiVerdict != null) {
                    updatedVerdict = aiVerdict;
                }
            }
            params.put("openAiPojo", ai);
            params.put("openAiTranslatedSummary", translatedSummary);
            params.put("openAiFinalVerdict", updatedVerdict);
            runLogService.logStep(runId, WorkflowStep.OPENAI, Map.of("stage", "openai-summary"),
                    ai, true, null);

            ScreenerContext updated = ctx.toBuilder().params(params).build();

            if (next != null) {
                next.run(updated);
            } else {
                // Tail: mark final using last ScreenerOutput (decision) if present
                runLogService.markFinal(runId, WorkflowStep.OPENAI, ai.isHighConviction(), ai.getFinalVerdict());
            }
            return ScreenerOutput.builder()
                    .passed(true)
                    .finalVerdict(updatedVerdict)
                    .debug(Map.of("openAiAnalysis", response.getAnalysis(), "openAiJsonAnalysis", response.getJsonAnalysis(),
                            "openAiTranslatedSummary", translatedSummary, "openAiFinalVerdict", updatedVerdict))
                    .build();
        } catch (Exception e) {
            log.error("OpenAIUOW.run error: {}", e.getMessage(), e);
            Long runId = getRunId(ctx);
            runLogService.logStep(runId, WorkflowStep.OPENAI, Map.of("error", "execution-error"), Map.of("exception", e.toString()), false, e.getMessage());
        }
        return null;
    }

    private static Long getRunId(ScreenerContext ctx) {
        Object val = ctx.getParams() != null ? ctx.getParams().get("screenerRunId") : null;
        if (val instanceof Long l) return l;
        if (val instanceof Number n) return n.longValue();
        return null;
    }

    @Override
    public UnitOfWork next() {
        return next;
    }

    @Override
    public void onEntry(ScreenerContext ctx, String... tags) {
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
        log.debug("OpenAIUOW.match type={}, meta={}", type, meta);
    }
}
