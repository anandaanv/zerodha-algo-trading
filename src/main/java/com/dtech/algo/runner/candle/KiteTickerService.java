package com.dtech.algo.runner.candle;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.config.KiteConnectPool;
import com.dtech.kitecon.controller.BarSeriesHelper;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.Subscription;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.SubscriptionRepository;
import com.dtech.kitecon.service.MarketHoursService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages real-time data feed from Zerodha's KiteTicker WebSocket API.
 *
 * Uses the primary client from KiteConnectPool for the WebSocket connection.
 * Ticks flow to: BarSeriesHelper → LiveCandleCache + MarketDataWebSocketService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KiteTickerService implements OnTicks, OnConnect, OnDisconnect, OnError {

    private final BarSeriesHelper barSeriesHelper;
    private final KiteConnectConfig kiteConnectConfig;   // kept for backward compat init path
    private final KiteConnectPool kiteConnectPool;
    private final MarketHoursService marketHoursService;
    private final SubscriptionRepository subscriptionRepository;
    private final InstrumentRepository instrumentRepository;

    private KiteTicker kiteTicker;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicInteger ticksReceived = new AtomicInteger(0);
    private final AtomicInteger ticksProcessed = new AtomicInteger(0);
    private final AtomicLong lastTickAt = new AtomicLong(0);

    private final ExecutorService tickProcessorPool =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    private final ConcurrentMap<Long, Instrument> subscribedInstruments = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            KiteConnect kiteConnect = kiteConnectPool.getPrimaryClient();

            if (kiteConnect == null || kiteConnect.getAccessToken() == null) {
                log.warn("KiteTicker: no authenticated client available — skipping init. Complete /kite-login first.");
                return;
            }

            String apiKey      = kiteConnect.getClass().getDeclaredField("apiKey") != null
                    ? extractApiKey(kiteConnect) : "";
            String accessToken = kiteConnect.getAccessToken();

            log.info("Initialising KiteTicker (access token present=true)");
            kiteTicker = new KiteTicker(accessToken, extractApiKey(kiteConnect));
            kiteTicker.setOnTickerArrivalListener(this);
            kiteTicker.setOnConnectedListener(this);
            kiteTicker.setOnDisconnectedListener(this);
            kiteTicker.setOnErrorListener(this);

            connect();
        } catch (Exception e) {
            log.error("Error initialising KiteTicker", e);
        }
    }

    public void connect() {
        if (connected.get()) return;
        try {
            log.info("Connecting to KiteTicker...");
            kiteTicker.connect();
            ArrayList<Long> tokens = getSubscribedTokens();
            if (!tokens.isEmpty()) {
                kiteTicker.setMode(tokens, "full");
            }
        } catch (Exception e) {
            log.error("Error connecting to KiteTicker", e);
            scheduleReconnect();
        }
    }

    public void disconnect() {
        if (!connected.get() || kiteTicker == null) return;
        try {
            kiteTicker.disconnect();
        } catch (Exception e) {
            log.error("Error disconnecting from KiteTicker", e);
        }
    }

    /**
     * Returns true if a tick was received within the last 5 minutes.
     * Use this to detect trading holidays — on holidays the WebSocket
     * may be connected but Zerodha sends no ticks.
     */
    public boolean isMarketLive() {
        long last = lastTickAt.get();
        if (last == 0) return false;
        return (System.currentTimeMillis() - last) < 5 * 60 * 1000L;
    }

    public void subscribe(List<Instrument> instruments) {
        if (instruments == null || instruments.isEmpty()) return;
        if (!connected.get()) connect();

        try {
            ArrayList<Long> tokens = new ArrayList<>();
            List<String> intervals = List.of("MINUTE", "FIFTEEN_MINUTE", "HOUR");

            for (Instrument instrument : instruments) {
                long token = instrument.getInstrumentToken();
                tokens.add(token);
                subscribedInstruments.put(token, instrument);
                barSeriesHelper.registerInstrument(instrument.getTradingsymbol(), token, intervals);
            }

            if (connected.get() && !tokens.isEmpty()) {
                kiteTicker.subscribe(tokens);
                kiteTicker.setMode(tokens, "full");
                log.info("Subscribed to {} instrument tokens", tokens.size());
            }
        } catch (Exception e) {
            log.error("Error subscribing to instruments", e);
        }
    }

    public void unsubscribe(List<Instrument> instruments) {
        if (instruments == null || instruments.isEmpty()) return;
        try {
            ArrayList<Long> tokens = new ArrayList<>();
            for (Instrument instrument : instruments) {
                long token = instrument.getInstrumentToken();
                tokens.add(token);
                subscribedInstruments.remove(token);
            }
            if (connected.get() && !tokens.isEmpty()) {
                kiteTicker.unsubscribe(tokens);
                log.info("Unsubscribed from {} instrument tokens", tokens.size());
            }
        } catch (Exception e) {
            log.error("Error unsubscribing from instruments", e);
        }
    }

    // ─────────────────────────── KiteTicker callbacks ─────────────────────

    @Override
    public void onTicks(ArrayList<Tick> ticks) {
        if (ticks == null || ticks.isEmpty()) return;

        ticksReceived.addAndGet(ticks.size());
        lastTickAt.set(System.currentTimeMillis());

        tickProcessorPool.submit(() -> {
            try {
                List<DataTick> dataTicks = new ArrayList<>(ticks.size());
                for (Tick kiteTick : ticks) {
                    DataTick dt = convertTickToDataTick(kiteTick);
                    if (dt != null) dataTicks.add(dt);
                }
                int processed = barSeriesHelper.processTicks(dataTicks);
                ticksProcessed.addAndGet(processed);
            } catch (Exception e) {
                log.error("Error processing ticks", e);
            }
        });
    }

    /**
     * Convert a Kite SDK Tick to our internal DataTick.
     * All price/volume fields are now correctly mapped.
     */
    private DataTick convertTickToDataTick(Tick kiteTick) {
        try {
            DataTick dt = new DataTick();
            dt.setInstrumentToken(kiteTick.getInstrumentToken());
            dt.setLastTradedPrice(kiteTick.getLastTradedPrice());
            dt.setHighPrice(kiteTick.getHighPrice());
            dt.setLowPrice(kiteTick.getLowPrice());
            dt.setOpenPrice(kiteTick.getOpenPrice());
            dt.setClosePrice(kiteTick.getClosePrice());
            dt.setVolumeTradedToday(kiteTick.getVolumeTradedToday());
            dt.setLastTradedQuantity(kiteTick.getLastTradedQuantity());
            dt.setAverageTradePrice(kiteTick.getAverageTradePrice());
            dt.setTotalBuyQuantity(kiteTick.getTotalBuyQuantity());
            dt.setTotalSellQuantity(kiteTick.getTotalSellQuantity());
            dt.setLastTradedTime(kiteTick.getLastTradedTime());
            dt.setTickTimestamp(kiteTick.getTickTimestamp());
            dt.setOi(kiteTick.getOi());
            dt.setOiDayHigh(kiteTick.getOpenInterestDayHigh());
            dt.setOiDayLow(kiteTick.getOpenInterestDayLow());
            dt.setChange(kiteTick.getChange());
            dt.setDepth(kiteTick.getMarketDepth());
            return dt;
        } catch (Exception e) {
            log.error("Error converting KiteTick for token {}", kiteTick.getInstrumentToken(), e);
            return null;
        }
    }

    @Override
    public void onConnected() {
        connected.set(true);
        reconnectAttempts.set(0);
        log.info("Connected to KiteTicker WebSocket");

        ArrayList<Long> tokens = getSubscribedTokens();
        if (!tokens.isEmpty()) {
            try {
                kiteTicker.subscribe(tokens);
                kiteTicker.setMode(tokens, "full");
                log.info("Resubscribed to {} tokens after reconnect", tokens.size());
            } catch (Exception e) {
                log.error("Error resubscribing after connect", e);
            }
        } else {
            // First connect on startup — auto-subscribe all ACTIVE subscriptions from DB
            autoSubscribeFromDatabase();
        }
    }

    private void autoSubscribeFromDatabase() {
        try {
            Map<Long, Instrument> instrumentMap = new LinkedHashMap<>();

            // 1. Collect DB ACTIVE subscriptions
            List<Subscription> active = subscriptionRepository.findAllByStatus("ACTIVE");
            for (Subscription sub : active) {
                String symbol = sub.getTradingSymbol();
                if (symbol == null || symbol.startsWith("INDEX-")) continue;
                instrumentRepository.findAllByTradingsymbol(symbol).stream()
                        .filter(i -> "NSE".equals(i.getExchange()))
                        .findFirst()
                        .ifPresent(inst -> instrumentMap.putIfAbsent(inst.getInstrumentToken(), inst));
            }
            int dbCount = instrumentMap.size();

            // 2. Also fetch and subscribe FNO underlying EQ instruments
            List<String> fnoUnderlyings = instrumentRepository.findDistinctFutureUnderlyingNames();
            for (String name : fnoUnderlyings) {
                instrumentRepository.findAllByTradingsymbol(name).stream()
                        .filter(i -> "NSE".equals(i.getExchange()))
                        .findFirst()
                        .ifPresent(inst -> instrumentMap.putIfAbsent(inst.getInstrumentToken(), inst));
            }
            int fnoCount = instrumentMap.size() - dbCount;

            // 3. Subscribe to deduplicated set
            List<Instrument> instruments = new ArrayList<>(instrumentMap.values());
            if (!instruments.isEmpty()) {
                log.info("Auto-subscribing to {} instruments ({} from DB, {} FNO) on startup",
                        instruments.size(), dbCount, fnoCount);
                subscribe(instruments);
            } else {
                log.info("No active subscriptions or FNO underlyings found — skipping auto-subscribe");
            }
        } catch (Exception e) {
            log.error("Error auto-subscribing from database on startup", e);
        }
    }

    @Override
    public void onDisconnected() {
        connected.set(false);
        log.warn("Disconnected from KiteTicker WebSocket");
        if (marketHoursService.isMarketOpenNow()) {
            scheduleReconnect();
        }
    }

    @Override
    public void onError(Exception exception) {
        log.error("KiteTicker error", exception);
        if (exception instanceof IOException && connected.get()) {
            connected.set(false);
            scheduleReconnect();
        }
    }

    @Override public void onError(KiteException e) { log.error("KiteTicker KiteException: {}", e.getMessage()); }
    @Override public void onError(String error)     { log.error("KiteTicker error string: {}", error); }

    // ─────────────────────────── Scheduled tasks ──────────────────────────

    @Scheduled(fixedRate = 60_000)
    public void healthCheck() {
        if (kiteTicker == null) return;
        if (!connected.get() && marketHoursService.isMarketOpenNow()) {
            log.warn("KiteTicker health check: not connected during market hours — reconnecting");
            connect();
        }
        log.info("KiteTicker stats — subscribed={} received={} processed={}",
                subscribedInstruments.size(), ticksReceived.get(), ticksProcessed.get());
    }

    @Scheduled(fixedRate = 3_600_000)
    public void checkAccessToken() {
        try {
            KiteConnect kc = kiteConnectPool.getPrimaryClient();
            if (kc == null) return;
            String currentToken = kc.getAccessToken();
            // Re-read from pool (which reads from DB) to detect external token refresh
            kiteConnectPool.loadFromUserKiteConfigs();
            String newToken = kiteConnectPool.getPrimaryClient() != null
                    ? kiteConnectPool.getPrimaryClient().getAccessToken() : null;
            if (newToken != null && !newToken.equals(currentToken)) {
                log.info("Access token changed — reinitialising KiteTicker");
                disconnect();
                reconnectAttempts.set(0);
                init();
            }
        } catch (Exception e) {
            log.error("Error checking access token", e);
        }
    }

    @Scheduled(cron = "0 0 0 * * ?")
    public void resetCounters() {
        ticksReceived.set(0);
        ticksProcessed.set(0);
    }

    @PreDestroy
    public void shutdown() {
        disconnect();
        tickProcessorPool.shutdown();
        try {
            if (!tickProcessorPool.awaitTermination(5, TimeUnit.SECONDS)) {
                tickProcessorPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            tickProcessorPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ─────────────────────────── Helpers ──────────────────────────────────

    private ArrayList<Long> getSubscribedTokens() {
        return new ArrayList<>(subscribedInstruments.keySet());
    }

    private void scheduleReconnect() {
        int attempts = reconnectAttempts.incrementAndGet();
        int delay = Math.min(30, (int) Math.pow(2, attempts));
        CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS).execute(this::connect);
        log.info("Reconnect attempt {} scheduled in {}s", attempts, delay);
    }

    /** Reflectively extract apiKey from KiteConnect (it's set but not exposed via getter). */
    private String extractApiKey(KiteConnect kc) {
        try {
            var f = KiteConnect.class.getDeclaredField("apiKey");
            f.setAccessible(true);
            return (String) f.get(kc);
        } catch (Exception e) {
            return "";
        }
    }
}
