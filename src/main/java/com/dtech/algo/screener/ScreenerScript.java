package com.dtech.algo.screener;

@FunctionalInterface
public interface ScreenerScript {
    ScreenerOutput evaluate(ScreenerContext ctx, SignalCallback callback);
}
