package com.dtech.algo.screener.runtime;

import com.dtech.algo.screener.ScreenerContext;
import com.dtech.algo.screener.SignalCallback;
import com.dtech.algo.service.AlertQueueService;
import com.dtech.algo.service.AlertQueueService.AlertType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScreenerAlertCallback implements SignalCallback {

    private final AlertQueueService alertQueueService;

    @Override
    public void onEntry(ScreenerContext ctx, String... tags) {
        publish(ctx, true, tags);
    }

    @Override
    public void onExit(ScreenerContext ctx, String... tags) {
        publish(ctx, false, tags);
    }

    @Override
    public void onEvent(String type, ScreenerContext ctx, Map<String, Object> meta) {
        // Optional custom events; no-op for now
        log.debug("Screener event: type={}, symbol={}, tf={}, meta={}",
                safe(ctx.getSymbol()), safe(ctx.getTimeframe()), meta);
    }

    private void publish(ScreenerContext ctx, boolean isEntry, String... tags) {
        String symbol = ctx.getSymbol() != null ? ctx.getSymbol() : String.valueOf(ctx.getParam("symbol"));
        String timeframe = ctx.getTimeframe() != null ? ctx.getTimeframe() : String.valueOf(ctx.getParam("timeframe"));
        String alias = "wave";
        BarSeries series = ctx.getSeries(alias);
        if (series == null) {
            // fallback: pick any available series
            if (ctx.getAliases() != null && !ctx.getAliases().isEmpty()) {
                series = ctx.getAliases().values().iterator().next();
            }
        }
        double price = 0.0;
        try {
            if (series != null) {
                int i = Math.max(0, ctx.getNowIndex());
                price = series.getBar(i).getClosePrice().doubleValue();
            }
        } catch (Exception ignore) {
        }

        AlertType type = isEntry ? AlertType.ASTA_BUY : AlertType.ASTA_SELL;
        String message = isEntry ? "IMP-Tech: Entry" : "IMP-Tech: Exit";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tags", tags);
        metadata.put("timestamp", LocalDateTime.now().toString());

        if (symbol == null || "null".equals(symbol)) symbol = "UNKNOWN";
        if (timeframe == null || "null".equals(timeframe)) timeframe = "UNKNOWN";

        alertQueueService.addAlert(type, symbol, timeframe, price, message, metadata);
        log.info("Published {} for {}@{} price={} tags={}", type, symbol, timeframe, price, String.join(",", tags));
    }

    private String safe(Object v) {
        return v == null ? "null" : v.toString();
    }
}
