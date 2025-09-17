package com.dtech.algo.screener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public class OpenAIUOW implements UnitOfWork {

    private final String promptJson;
    private final UnitOfWork next;

    @Override
    public void run(ScreenerContext ctx) {
        // Placeholder: in future, use promptJson + ctx to call OpenAI and possibly emit signals
        log.debug("OpenAIUOW.run called for screener id={}, symbol={}", 
                ctx.getScreener() != null ? ctx.getScreener().getId() : null, ctx.getSymbol());
        if (next != null) {
            next.run(ctx);
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
