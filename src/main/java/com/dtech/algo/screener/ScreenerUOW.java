package com.dtech.algo.screener;

import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public class ScreenerUOW implements UnitOfWork {

    private final ScreenerRegistryService registry;
    private final long screenerId;
    private final UnitOfWork next;

    @Override
    public void run(ScreenerContext ctx) {
        SignalCallback cb = next != null ? next : NOOP;
        ScreenerOutput output = getScreenerOutput(ctx, cb);
        if (output.isPassed()) {
            next().run(ctx);
        }
    }

    private ScreenerOutput getScreenerOutput(ScreenerContext ctx, SignalCallback cb) {
        ScreenerOutput output = registry.run(screenerId, ctx, cb);
        return output;
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

    private static final SignalCallback NOOP = null;
}
