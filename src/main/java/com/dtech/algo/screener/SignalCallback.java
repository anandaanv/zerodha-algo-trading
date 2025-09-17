package com.dtech.algo.screener;

import java.util.Map;

public interface SignalCallback {
    void onEntry(ScreenerContext ctx, String... tags);
    void onExit(ScreenerContext ctx, String... tags);

    default void onEvent(String type, ScreenerContext ctx, Map<String, Object> meta) {
        // no-op by default
    }

    default void match(String type, ScreenerContext ctx, Map<String, Object> meta) {
        // no-op by default
    }
}
