package com.dtech.kitecon.service;

import com.dtech.algo.runner.candle.LiveCandleCache;
import com.dtech.algo.runner.candle.MarketDataWebSocketService;
import com.dtech.algo.series.Interval;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.Subscription;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import com.dtech.kitecon.screener.elliott.service.ElliottTradeMonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Fires at :01 of every minute during market hours.
 *
 * For the just-closed 1-minute bar:
 *   1. Reads the completed bar from LiveCandleCache (tick-aggregated)
 *   2. Upserts it into the candle DB table
 *   3. Broadcasts it to WebSocket clients as a closed bar
 *
 * For 5-min / 15-min / hourly boundaries:
 *   Broadcasts the live cache bar to WebSocket clients.
 *   DB persistence for those intervals is handled by SubscriptionUpdaterJob
 *   via the broker's historical API (more reliable than tick aggregation).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CandleCloseScheduler {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleCache liveCache;
    private final MarketHoursService marketHoursService;
    private final SubscriptionRepository subscriptionRepository;
    private final MarketDataWebSocketService webSocketService;
    private final InstrumentRepository instrumentRepository;
    private final CandleRepository candleRepository;
    private final ElliottTradeMonitoringService elliottTradeMonitoringService;

    @Scheduled(cron = "1 * * * * *") // fires at :01 of every minute
    public void onMinuteClose() {
        if (!marketHoursService.isMarketOpenNow()) return;

        ZonedDateTime now = Instant.now().atZone(IST);
        // The minute that just closed started 1 minute ago
        Instant closedBarStart = now.truncatedTo(ChronoUnit.MINUTES)
                .minus(1, ChronoUnit.MINUTES).toInstant();
        int closedMinute = now.getMinute() == 0 ? 59 : now.getMinute() - 1;

        List<Subscription> active = subscriptionRepository.findAllByStatus("ACTIVE");
        if (active.isEmpty()) return;

        log.debug("CandleCloseScheduler: processing {} subscriptions for minute {}",
                active.size(), closedMinute);

        // ── 1-min: persist from live cache ───────────────────────────────
        for (Subscription sub : active) {
            try {
                String symbol = sub.getTradingSymbol();
                if (symbol == null || symbol.startsWith("INDEX-")) continue;

                OhlcBarDTO bar = liveCache.getCompletedBar(symbol, Interval.OneMinute, closedBarStart);
                if (bar == null) continue;

                upsertCandle(symbol, Interval.OneMinute, closedBarStart, bar);
                webSocketService.broadcastBar(symbol, Interval.OneMinute.name(), bar, false);

            } catch (Exception e) {
                log.warn("Error processing 1-min candle for {}: {}", sub.getTradingSymbol(), e.getMessage());
            }
        }

        // ── 5-min boundary: broadcast ─────────────────────────────────────
        if (closedMinute % 5 == 4) {
            broadcastInterval(active, Interval.FiveMinute);
        }

        // ── 15-min boundary: broadcast ────────────────────────────────────
        if (closedMinute % 15 == 14) {
            broadcastInterval(active, Interval.FifteenMinute);
        }

        // ── Hourly boundary: broadcast ────────────────────────────────────
        if (closedMinute == 59 || closedMinute == 29) {
            broadcastInterval(active, Interval.OneHour);
        }

        // ── Elliott trade monitoring ──────────────────────────────────────────────
        try {
            elliottTradeMonitoringService.checkOpenSuggestions();
        } catch (Exception e) {
            log.warn("Error in Elliott trade monitoring: {}", e.getMessage());
        }
    }

    private void broadcastInterval(List<Subscription> active, Interval interval) {
        for (Subscription sub : active) {
            try {
                String symbol = sub.getTradingSymbol();
                if (symbol == null || symbol.startsWith("INDEX-")) continue;
                OhlcBarDTO bar = liveCache.getLiveCandle(symbol, interval);
                if (bar != null) {
                    webSocketService.broadcastBar(symbol, interval.name(), bar, false);
                }
            } catch (Exception e) {
                log.warn("Error broadcasting {} for {}: {}", interval, sub.getTradingSymbol(), e.getMessage());
            }
        }
    }

    private void upsertCandle(String symbol, Interval interval, Instant timestamp, OhlcBarDTO bar) {
        // Look up instrument by trading symbol (prefer NSE equity)
        List<Instrument> instruments = instrumentRepository.findAllByTradingsymbol(symbol);
        Instrument instrument = instruments.stream()
                .filter(i -> "NSE".equals(i.getExchange()))
                .findFirst()
                .orElse(instruments.isEmpty() ? null : instruments.get(0));

        if (instrument == null) {
            log.trace("Instrument not found for symbol {}, skipping candle persist", symbol);
            return;
        }

        // Find the latest candle for this instrument/interval and check if it's for this timestamp
        Candle candle = candleRepository
                .findFirstByInstrumentAndTimeframeOrderByTimestampDesc(instrument, interval);

        if (candle == null || !candle.getTimestamp().equals(timestamp)) {
            candle = new Candle();
            candle.setInstrument(instrument);
            candle.setTimeframe(interval);
            candle.setTimestamp(timestamp);
        }

        candle.setOpen(bar.getOpen());
        candle.setHigh(bar.getHigh());
        candle.setLow(bar.getLow());
        candle.setClose(bar.getClose());
        candle.setVolume((long) bar.getVolume());
        candleRepository.save(candle);
    }
}
