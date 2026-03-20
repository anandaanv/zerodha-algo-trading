package com.dtech.algo.runner.candle;

import com.dtech.chartdata.model.OhlcBarDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Broadcasts real-time bar updates to subscribed TradingView chart clients via STOMP WebSocket.
 *
 * Topic format: /topic/bars/{SYMBOL}/{INTERVAL}
 * Example:      /topic/bars/TCS/OneMinute
 *
 * Called from:
 * - BarSeriesHelper.processTick()  → on every tick (isPartial=true, live bar update)
 * - CandleCloseScheduler           → on bar close  (isPartial=false, final bar)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataWebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcastBar(String symbol, String interval, OhlcBarDTO bar, boolean isPartial) {
        BarUpdateMessage msg = BarUpdateMessage.builder()
                .symbol(symbol)
                .interval(interval)
                .time(bar.getTime())
                .open(bar.getOpen())
                .high(bar.getHigh())
                .low(bar.getLow())
                .close(bar.getClose())
                .volume(bar.getVolume())
                .isPartial(isPartial)
                .build();

        String destination = "/topic/bars/" + symbol + "/" + interval;
        messagingTemplate.convertAndSend(destination, msg);
        log.trace("Broadcast {} bar {} @ {} c={}", isPartial ? "live" : "closed", symbol, bar.getTime(), bar.getClose());
    }
}
