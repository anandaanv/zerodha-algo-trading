package com.dtech.algo.screener;

public interface UnitOfWork extends SignalCallback {
    ScreenerOutput run(ScreenerContext ctx);
    UnitOfWork next();
}
