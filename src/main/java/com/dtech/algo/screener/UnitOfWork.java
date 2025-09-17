package com.dtech.algo.screener;

public interface UnitOfWork extends SignalCallback {
    void run(ScreenerContext ctx);
    UnitOfWork next();
}
