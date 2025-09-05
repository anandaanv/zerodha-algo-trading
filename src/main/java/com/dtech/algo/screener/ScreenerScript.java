package com.dtech.algo.screener;

@FunctionalInterface
public interface ScreenerScript {
    void evaluate(ScreenerContext ctx, SignalCallback callback);
}
