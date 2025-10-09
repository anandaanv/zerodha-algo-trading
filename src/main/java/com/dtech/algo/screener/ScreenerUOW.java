package com.dtech.algo.screener;

import com.dtech.algo.screener.enums.WorkflowStep;
import com.dtech.algo.screener.kotlinrunner.KotlinScriptExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public class ScreenerUOW implements UnitOfWork {

    private final KotlinScriptExecutor registry;
    private final String code;
    private final UnitOfWork next;
    private final com.dtech.algo.screener.runtime.ScreenerRunLogService runLogService;
    private final Object entry;

    @Override
    public ScreenerOutput run(ScreenerContext ctx) {
        SignalCallback cb = next != null ? next : NOOP;
        ScreenerOutput output = getScreenerOutput(ctx, cb);

        Long runId = getRunId(ctx);
        // Log SCRIPT step output
        Map<String, Object> input = Map.of(
                "symbol", ctx.getSymbol(),
                "timeframe", ctx.getTimeframe(),
                "nowIndex", ctx.getNowIndex()
        );
        runLogService.logStep(runId, WorkflowStep.SCRIPT, input, output, output != null && output.isPassed(), null);

        // Stash last ScreenerOutput for later steps to use (e.g., to mark final verdict at the tail)
        Map<String, Object> params = ctx.getParams() == null ? new HashMap<>() : new HashMap<>(ctx.getParams());
        params.put("lastScreenerOutput", output);
        ScreenerContext updated = ctx.toBuilder().params(params).build();

        if (next != null) {
            if (output != null && output.isPassed()) {
                return next().run(updated);
            } else {
                // If not passed and this is the last step, mark final as FAIL
                if (next == null) {
                    runLogService.markFinal(runId, false, output != null ? output.getFinalVerdict() : null);
                }
            }
        } else {
            // Last step: mark final using ScreenerOutput
            runLogService.markFinal(runId, output != null && output.isPassed(), output != null ? output.getFinalVerdict() : null);
        }
        return output;
    }

    private ScreenerOutput getScreenerOutput(ScreenerContext ctx, SignalCallback cb) {
        try {
            return registry.invokeMethod(entry, "screener", ctx, cb);
        } catch (Exception e) {
            // Log failure as a step with error
            Long runId = getRunId(ctx);
            runLogService.logStep(runId, WorkflowStep.SCRIPT, Map.of("error", "execution-error"), Map.of("exception", e.toString()), false, e.getMessage());
            throw new RuntimeException(e);
        }
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

    // This UOW itself does not produce callbacks, it delegates through 'next' when used as a callback
    @Override
    public void onEntry(ScreenerContext ctx, String... tags) {
        if (next != null) next.onEntry(ctx, tags);
    }

    @Override
    public void onExit(ScreenerContext ctx, String... tags) {
        if (next != null) next.onExit(ctx, tags);
    }

    @Override
    public void onEvent(String type, ScreenerContext ctx, Map<String, Object> meta) {
        if (next != null) next.onEvent(type, ctx, meta);
    }

    @Override
    public void match(String type, ScreenerContext ctx, Map<String, Object> meta) {
        if (next != null) next.match(type, ctx, meta);
    }

    private static final SignalCallback NOOP = new SignalCallback() {
        @Override
        public void onEntry(ScreenerContext ctx, String... tags) { }

        @Override
        public void onExit(ScreenerContext ctx, String... tags) { }
    };
}
