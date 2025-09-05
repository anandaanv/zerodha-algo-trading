package com.dtech.algo.runner.candle;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.controller.BarSeriesHelper;
import com.dtech.kitecon.data.Instrument;
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
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for managing real-time data feed from Zerodha's KiteTicker WebSocket API
 * Handles the WebSocket connection, subscribes to instruments, and processes incoming ticks
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KiteTickerService implements OnTicks, OnConnect,
        OnDisconnect, OnError {

    private final BarSeriesHelper barSeriesHelper;
    private final KiteConnectConfig kiteConnectConfig;

    private KiteTicker kiteTicker;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicInteger ticksReceived = new AtomicInteger(0);
    private final AtomicInteger ticksProcessed = new AtomicInteger(0);

    // Thread pool for processing ticks to avoid blocking the WebSocket thread
    private final ExecutorService tickProcessorPool = 
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    // In-memory cache of currently subscribed instruments
    private final ConcurrentMap<Long, Instrument> subscribedInstruments = new ConcurrentHashMap<>();

    /**
     * Initialize the KiteTicker connection
     */
    @PostConstruct
    public void init() {
        try {
            kiteConnectConfig.initFromDatabase();
            String apiKey = kiteConnectConfig.getApiKey();
            String accessToken = kiteConnectConfig.getKiteConnect().getAccessToken();

            log.info("Initializing KiteTicker with API key: {}", apiKey);
            kiteTicker = new KiteTicker(accessToken, apiKey);

            // Register this service as the listener for KiteTicker events
            kiteTicker.setOnTickerArrivalListener(this);
            kiteTicker.setOnConnectedListener(this);
            kiteTicker.setOnDisconnectedListener(this);
            kiteTicker.setOnErrorListener(this);

            // Connect to the WebSocket
            connect();

            log.info("KiteTicker initialized successfully");
        } catch (Exception e) {
            log.error("Error initializing KiteTicker", e);
        }
    }

    /**
     * Connect to the KiteTicker WebSocket
     */
    public void connect() {
        if (connected.get()) {
            log.debug("Already connected to KiteTicker");
            return;
        }

        try {
            log.info("Connecting to KiteTicker...");
            kiteTicker.connect();
            kiteTicker.setMode(getSubscribedTokens(), "full");
        } catch (Exception e) {
            log.error("Error connecting to KiteTicker", e);
            scheduleReconnect();
        }
    }

    /**
     * Disconnect from the KiteTicker WebSocket
     */
    public void disconnect() {
        if (!connected.get()) {
            return;
        }

        try {
            log.info("Disconnecting from KiteTicker...");
            kiteTicker.disconnect();
        } catch (Exception e) {
            log.error("Error disconnecting from KiteTicker", e);
        }
    }

    /**
     * Subscribe to a list of instruments
     * 
     * @param instruments List of instruments to subscribe to
     */
    public void subscribe(List<Instrument> instruments) {
        if (instruments == null || instruments.isEmpty()) {
            log.warn("No instruments provided for subscription");
            return;
        }

        if(!connected.get()) {
             connect();
        }

        try {
            log.info("Subscribing to {} instruments", instruments.size());

            ArrayList<Long> tokens = new ArrayList<>();
            List<String> intervals = List.of("MINUTE", "FIFTEEN_MINUTE", "HOUR");

            for (Instrument instrument : instruments) {
                long token = instrument.getInstrumentToken();
                tokens.add(token);
                subscribedInstruments.put(token, instrument);

                // Register the instrument with BarSeriesHelper for each interval
                barSeriesHelper.registerInstrument(
                        instrument.getTradingsymbol(), 
                        token, 
                        intervals
                );
            }

            if (connected.get() && !tokens.isEmpty()) {
                kiteTicker.subscribe(tokens);
                kiteTicker.setMode(tokens, "full");
                log.info("Subscribed to {} instrument tokens", tokens.size());
            } else {
                log.warn("Not connected to KiteTicker, instruments will be subscribed when connected");
            }
        } catch (Exception e) {
            log.error("Error subscribing to instruments", e);
        }
    }

    /**
     * Unsubscribe from a list of instruments
     * 
     * @param instruments List of instruments to unsubscribe from
     */
    public void unsubscribe(List<Instrument> instruments) {
        if (instruments == null || instruments.isEmpty()) {
            return;
        }

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

    /**
     * Get a list of currently subscribed tokens
     */
    private ArrayList<Long> getSubscribedTokens() {
        return new ArrayList<>(subscribedInstruments.keySet());
    }

    /**
     * Schedule a reconnection attempt with exponential backoff
     */
    private void scheduleReconnect() {
        int attempts = reconnectAttempts.incrementAndGet();
        int delay = Math.min(30, (int) Math.pow(2, attempts)); // Exponential backoff, max 30 seconds

        log.info("Scheduling reconnection attempt {} in {} seconds", attempts, delay);

        CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS).execute(() -> {
            log.info("Attempting to reconnect to KiteTicker (attempt {})", attempts);
            connect();
        });
    }

    /**
     * Check if access token needs to be refreshed
     */
    @Scheduled(fixedRate = 3600000) // Check every hour
    public void checkAccessToken() {
        try {
            log.debug("Checking if access token needs to be refreshed");
            String currentToken = kiteConnectConfig.getKiteConnect().getAccessToken();

            // Logic to check if token is expired or about to expire would go here
            // For simplicity, we're assuming the config service handles token refresh
            String newToken = kiteConnectConfig.getKiteConnect().getAccessToken();

            if (!currentToken.equals(newToken)) {
                log.info("Access token has been refreshed, reconnecting to KiteTicker");
                disconnect();
                reconnectAttempts.set(0); // Reset reconnect attempts
                init(); // Reinitialize with new token
            }
        } catch (Exception e) {
            log.error("Error checking/refreshing access token", e);
        }
    }

    /**
     * Scheduled health check for the WebSocket connection
     */
    @Scheduled(fixedRate = 60000) // Check every minute
    public void healthCheck() {
        if (!connected.get()) {
            log.warn("KiteTicker not connected during health check, attempting to reconnect");
            connect();
        } else {
            log.debug("KiteTicker connection health check passed");
        }

        // Log statistics
        log.info("KiteTicker stats - Subscribed instruments: {}, Ticks received: {}, Ticks processed: {}",
                subscribedInstruments.size(), ticksReceived.get(), ticksProcessed.get());
    }

    /**
     * Reset the tick counters (can be called periodically to avoid overflow)
     */
    @Scheduled(cron = "0 0 0 * * ?") // Reset at midnight every day
    public void resetCounters() {
        ticksReceived.set(0);
        ticksProcessed.set(0);
        log.info("Reset tick counters");
    }

    /**
     * Clean shutdown of resources
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down KiteTickerService");
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
        log.info("KiteTickerService shutdown complete");
    }

    //-------------------- KiteTicker Interface Implementations --------------------//

    /**
     * Called when ticks are received from the WebSocket
     * Delegates to BarSeriesHelper for business logic processing
     */
    @Override
    public void onTicks(ArrayList<Tick> ticks) {
        if (ticks == null || ticks.isEmpty()) {
            return;
        }

        int count = ticks.size();
        ticksReceived.addAndGet(count);
        log.debug("Received {} ticks from KiteTicker", count);

        // Submit tick processing to the thread pool to avoid blocking the WebSocket thread
        tickProcessorPool.submit(() -> {
            try {
                List<DataTick> dataTicks = new ArrayList<>(count);

                // Convert Kite ticks to our internal DataTick format
                for (Tick kiteTick : ticks) {
                    DataTick dataTick = convertTickToDataTick(kiteTick);
                    if (dataTick != null) {
                        dataTicks.add(dataTick);
                    }
                }

                // Process all ticks in bulk
                int processed = barSeriesHelper.processTicks(dataTicks);
                ticksProcessed.addAndGet(processed);

                if (processed < dataTicks.size()) {
                    log.warn("Only processed {} out of {} ticks", processed, dataTicks.size());
                }
            } catch (Exception e) {
                log.error("Error processing ticks", e);
            }
        });
    }

    /**
     * Convert a KiteTick to our internal DataTick format
     */
    private DataTick convertTickToDataTick(Tick kiteTick) {
        try {
            // This is a simplified conversion, you'd need to map all relevant fields
            DataTick dataTick = new DataTick();
            dataTick.setInstrumentToken(kiteTick.getInstrumentToken());
//            dataTick.setLastPrice(kiteTick.getLastTradedPrice());
//            dataTick.setVolume(kiteTick.getVolumeTradedToday());
//            dataTick.setTimestamp(kiteTick.getLastTradedTime());

            // Additional fields would be mapped here

            return dataTick;
        } catch (Exception e) {
            log.error("Error converting KiteTick to DataTick", e);
            return null;
        }
    }

    /**
     * Called when connected to the WebSocket
     */
    @Override
    public void onConnected() {
        connected.set(true);
        reconnectAttempts.set(0);
        log.info("Connected to KiteTicker WebSocket");

        // Resubscribe to instruments
        ArrayList<Long> tokens = getSubscribedTokens();
        if (!tokens.isEmpty()) {
            try {
                kiteTicker.subscribe(tokens);
                kiteTicker.setMode(tokens, "full");
                log.info("Resubscribed to {} instrument tokens after connection", tokens.size());
            } catch (Exception e) {
                log.error("Error resubscribing to instruments after connection", e);
            }
        }
    }

    /**
     * Called when disconnected from the WebSocket
     */
    @Override
    public void onDisconnected() {
        connected.set(false);
        log.warn("Disconnected from KiteTicker WebSocket");
    }

    /**
     * Called when an error occurs on the WebSocket
     */
    @Override
    public void onError(Exception exception) {
        log.error("KiteTicker WebSocket error", exception);

        if (
                exception instanceof IOException) {
            // These are likely connection issues, so attempt reconnect
            if (connected.get()) {
                connected.set(false);
                scheduleReconnect();
            }
        }
    }

    @Override
    public void onError(KiteException kiteException) {

    }

    @Override
    public void onError(String error) {

    }
}
