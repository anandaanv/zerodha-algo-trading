package com.dtech.kitecon.service;

import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.algo.series.IntervalBarSeries;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Service for handling batch updates to the database for completed candles
 * Uses a queue to collect candles that need to be persisted and processes them in batches
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseBatchUpdateService {

    // Repository dependencies would be injected here
    // private final CandleRepository candleRepository;
    // private final InstrumentRepository instrumentRepository;

    // Queue to store configs that need to be processed
    private final Queue<BarSeriesConfig> updateQueue = new ConcurrentLinkedQueue<>();

    // Set to track what's already in the queue to avoid duplicates
    private final Set<String> queuedConfigs = new HashSet<>();

    // Statistics for monitoring
    private long totalProcessed = 0;
    private long totalErrors = 0;
    private LocalDateTime lastProcessTime = LocalDateTime.now();

    /**
     * Add a config to the update queue if it's not already queued
     * 
     * @param config The BarSeriesConfig to queue for update
     * @return true if added to queue, false if already in queue
     */
    public boolean addToQueue(BarSeriesConfig config) {
        String configKey = createConfigKey(config);

        // Use the set for O(1) lookup to see if this config is already queued
        synchronized (queuedConfigs) {
            if (queuedConfigs.contains(configKey)) {
                log.debug("Config already in queue: {}", configKey);
                return false;
            }

            // Add to both queue and tracking set
            updateQueue.add(config);
            queuedConfigs.add(configKey);
            log.debug("Added to update queue: {}", configKey);
            return true;
        }
    }

    /**
     * Process all items in the queue - runs every minute via scheduler
     */
    @Scheduled(fixedRate = 60000) // Run every minute
    @Transactional
    public void processQueue() {
        int processedCount = 0;
        int errorCount = 0;
        Set<String> processedKeys = new HashSet<>();

        log.info("Starting batch processing of update queue. Queue size: {}", updateQueue.size());
        lastProcessTime = LocalDateTime.now();

        // Process queue until empty
        BarSeriesConfig config;
        while ((config = updateQueue.poll()) != null) {
            String configKey = createConfigKey(config);
            processedKeys.add(configKey);

            try {
                // Here we would perform the actual database update
                // candleRepository.saveLatestCandle(config);
                log.debug("Processed update for: {}", configKey);
                processedCount++;
            } catch (Exception e) {
                log.error("Error processing update for: {}", configKey, e);
                errorCount++;

                // Implement retry logic - add back to queue if it's a transient error
                if (isTransientError(e) && errorCount < 3) {
                    updateQueue.add(config);
                    log.debug("Re-queued after transient error: {}", configKey);
                }
            }
        }

        // Clean up the tracking set
        synchronized (queuedConfigs) {
            queuedConfigs.removeAll(processedKeys);
        }

        // Update statistics
        totalProcessed += processedCount;
        totalErrors += errorCount;

        log.info("Batch processing complete. Processed: {}, Errors: {}, Total processed: {}, Total errors: {}",
                processedCount, errorCount, totalProcessed, totalErrors);
    }

    /**
     * Create a unique key for a config to track in the queue
     */
    private String createConfigKey(BarSeriesConfig config) {
        return config.getInstrument() + "_" + config.getInterval();
    }

    /**
     * Check if an exception represents a transient error that can be retried
     */
    private boolean isTransientError(Exception e) {
        // Check for known transient errors like connection issues
        return e instanceof java.sql.SQLTransientConnectionException ||
               e.getCause() instanceof java.net.SocketTimeoutException;
    }

    /**
     * Get queue size for monitoring
     */
    public int getQueueSize() {
        return updateQueue.size();
    }

    /**
     * Get statistics for monitoring
     */
    public String getStatistics() {
        return String.format("Queued: %d, Total processed: %d, Total errors: %d, Last process time: %s",
                updateQueue.size(), totalProcessed, totalErrors, lastProcessTime);
    }
}
