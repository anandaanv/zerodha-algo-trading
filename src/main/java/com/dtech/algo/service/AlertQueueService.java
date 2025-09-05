package com.dtech.algo.service;

import com.dtech.algo.controller.dto.ChartAnalysisRequest;
import com.dtech.algo.controller.dto.ChartAnalysisResponse;
import com.dtech.algo.series.Interval;
import lombok.Data;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for handling alert queues dynamically
 * Similar to DatabaseBatchUpdateService but for trading alerts
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertQueueService {

    // Map of alert type to its queue
    private final Map<AlertType, Queue<AlertEntry>> alertQueues = new ConcurrentHashMap<>();

    // Map of alert type to tracking set (to avoid duplicates)
    private final Map<AlertType, Set<String>> alertTracking = new ConcurrentHashMap<>();

    // Statistics tracking
    private final Map<AlertType, AtomicInteger> alertCounts = new ConcurrentHashMap<>();

    // Dependencies for follow-up analysis
    private final ChartAnalysisService chartAnalysisService;

    // Scheduler configuration
    @Value("${alerts.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${alerts.scheduler.delay.ms:5000}")
    private long schedulerDelayMs;

    // Comma-separated alert types to listen for (e.g., "SWING_BUY,ASTA_BUY")
    @Value("${alerts.scheduler.types:SWING_BUY,ASTA_BUY,SWING_SELL,ASTA_SELL}")
    private String schedulerTypesProp;

    // Comma-separated alert types that should trigger WhatsApp notifications
    @Value("${alerts.whatsapp.types:}")
    private String whatsappTypesProp;

    private Set<AlertType> listenedTypes = EnumSet.noneOf(AlertType.class);
    private Set<AlertType> whatsappTypes = EnumSet.of(AlertType.SWING_BUY, AlertType.SWING_SELL, AlertType.ASTA_SELL, AlertType.ASTA_BUY);

    @PostConstruct
    public void initAlertListeningTypes() {
        if (schedulerTypesProp == null || schedulerTypesProp.isBlank()) {
            listenedTypes = EnumSet.noneOf(AlertType.class);
        } else {
            Set<AlertType> set = EnumSet.noneOf(AlertType.class);
            for (String raw : schedulerTypesProp.split(",")) {
                String name = raw.trim();
                try {
                    set.add(AlertType.valueOf(name));
                } catch (IllegalArgumentException ex) {
                    log.warn("Unknown alert type in alerts.scheduler.types: {}", name);
                }
            }
            listenedTypes = set;
        }
        log.info("Alert scheduler listening types: {}", listenedTypes);
    }

    /**
     * Add alert to appropriate queue
     */
    public boolean addAlert(AlertType alertType, String symbol, String timeframe,
                           double price, String message, Map<String, Object> metadata) {

        // Get or create queue for this alert type
        Queue<AlertEntry> queue = alertQueues.computeIfAbsent(alertType, k -> new ConcurrentLinkedQueue<>());
        Set<String> tracking = alertTracking.computeIfAbsent(alertType, k -> ConcurrentHashMap.newKeySet());
        AtomicInteger counter = alertCounts.computeIfAbsent(alertType, k -> new AtomicInteger(0));

        // Create unique key to avoid duplicates
        String alertKey = String.format("%s-%s-%s-%.2f", symbol, timeframe, alertType.name(), price);

        // Check if already exists
        if (tracking.contains(alertKey)) {
            log.debug("Alert already exists in queue: {}", alertKey);
            return false;
        }

        // Create alert entry
        AlertEntry entry = AlertEntry.builder()
                .alertType(alertType)
                .symbol(symbol)
                .timeframe(timeframe)
                .price(price)
                .message(message)
                .metadata(metadata)
                .timestamp(LocalDateTime.now())
                .alertKey(alertKey)
                .build();

        // Add to queue and tracking
        boolean added = queue.offer(entry);
        if (added) {
            tracking.add(alertKey);
            counter.incrementAndGet();
            log.info("Added {} alert for {}: {}", alertType, symbol, message);
        }

        return added;
    }

    /**
     * Process alerts from a specific queue
     */
    public void processAlerts(AlertType alertType) {
        Queue<AlertEntry> queue = alertQueues.get(alertType);
        Set<String> tracking = alertTracking.get(alertType);

        if (queue == null || queue.isEmpty()) {
            return;
        }

        log.info("Processing {} alerts from {} queue", queue.size(), alertType);

        int processed = 0;
        while (!queue.isEmpty()) {
            AlertEntry entry = queue.poll();
            if (entry != null) {
                try {
                    processAlert(entry);
                    if (tracking != null) {
                        tracking.remove(entry.getAlertKey());
                    }
                    processed++;
                } catch (Exception e) {
                    log.error("Error processing alert: {}", entry, e);
                }
            }
        }

        log.info("Processed {} alerts from {} queue", processed, alertType);
    }

    /**
     * Process all alert queues
     */
    public void processAllAlerts() {
        alertQueues.keySet().forEach(this::processAlerts);
    }

    /**
     * Process individual alert entry
     */
    private void processAlert(AlertEntry entry) {
        // Generic hook for side-effects like persistence/notifications
        log.info("Processing alert: {} - {} at {} for {}",
                entry.getAlertType(), entry.getMessage(), entry.getPrice(), entry.getSymbol());
        // Extend here with DB persistence, notifications, etc.
    }

    /**
     * Scheduler that listens for specific alert types, triggers chart analysis with
     * derived timeframes (two-level parents + one child), then runs generic processing.
     */
    @Scheduled(fixedDelay = 5000)
    public void scheduledAlertAnalyzer() {
        if (!schedulerEnabled || listenedTypes.isEmpty()) {
            return;
        }
        for (AlertType type : listenedTypes) {
            Queue<AlertEntry> queue = alertQueues.get(type);
            if (queue == null || queue.isEmpty()) {
                continue;
            }
            AlertEntry entry;
            while ((entry = queue.poll()) != null) {
                // Ensure tracking key is removed so duplicates can be re-queued later if needed
                Set<String> tracking = alertTracking.get(type);
                if (tracking != null) {
                    tracking.remove(entry.getAlertKey());
                }

                try {
                    // Build and fire chart analysis request with derived timeframes
                    List<Interval> tfList = deriveFamilyTimeframes(entry.getTimeframe());
                    if (tfList.isEmpty()) {
                        log.warn("Skipping analysis for {}: unable to derive timeframes from '{}'",
                                entry.getSymbol(), entry.getTimeframe());
                    } else {
                        ChartAnalysisRequest request = ChartAnalysisRequest.builder()
                                .symbol(entry.getSymbol())
                                .timeframes(tfList)
                                .candleCount(300)
                                .build();

                        ChartAnalysisResponse response = chartAnalysisService.analyzeCharts(request);
                        logOpenAIOutcome(response, entry, tfList);
                    }
                } catch (Exception ex) {
                    log.error("Error during scheduled analysis for alert {}: {}", entry.getAlertType(), ex.getMessage(), ex);
                }

                // Continue with generic processing hook
                try {
                    processAlert(entry);
                } catch (Exception e) {
                    log.error("Error in generic alert processing: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Derive child + base + two parent timeframes from the alert's timeframe string.
     * Example: OneHour => [FifteenMinute, OneHour, Day, Week]
     */
    private List<Interval> deriveFamilyTimeframes(String baseTimeframeName) {
        List<Interval> result = new ArrayList<>();
        Interval base = tryInterval(baseTimeframeName);
        if (base == null) {
            return result;
        }

        // Parents via Interval.getParent()
        Interval parent1 = base.getParent();
        Interval parent2 = (parent1 != null) ? parent1.getParent() : null;

        // Child via helper (preferred mapping, otherwise any interval whose parent == base)
        Interval child = findChildInterval(base);

        // Compose list: child (if any) + base + parents (if any)
        if (child != null) {
            result.add(child);
        }
        result.add(base);
        if (parent1 != null) {
            result.add(parent1);
        }
        if (parent2 != null) {
            result.add(parent2);
        }

        // Deduplicate while preserving order
        LinkedHashSet<Interval> dedup = new LinkedHashSet<>(result);
        return new ArrayList<>(dedup);
    }

    private Interval tryInterval(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return Interval.valueOf(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Find a suitable child interval for a given base:
     * - Use preferred mapping for common bases
     * - Otherwise, select any interval whose parent is the base
     */
    private Interval findChildInterval(Interval base) {
        // Preferred child suggestions for common bases
        Map<String, String> preferred = new LinkedHashMap<>();
        preferred.put("OneHour", "FifteenMinute");
        preferred.put("FifteenMinute", "FiveMinute");
        preferred.put("Day", "OneHour");
        preferred.put("Week", "Day");
        preferred.put("Month", "Week");

        String suggested = preferred.get(base.name());
        if (suggested != null) {
            Interval candidate = tryInterval(suggested);
            if (candidate != null) {
                return candidate;
            }
        }

        // Fallback: scan all enum values to find one whose parent equals base
        for (Interval i : Interval.values()) {
            try {
                Interval p = i.getParent();
                if (p != null && p.equals(base)) {
                    return i;
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    /**
     * Get queue size for specific alert type
     */
    public int getQueueSize(AlertType alertType) {
        Queue<AlertEntry> queue = alertQueues.get(alertType);
        return queue != null ? queue.size() : 0;
    }

    /**
     * Get total alerts processed for specific type
     */
    public int getTotalAlertsCount(AlertType alertType) {
        AtomicInteger counter = alertCounts.get(alertType);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Get statistics for all alert types
     */
    public Map<AlertType, AlertStats> getAllStatistics() {
        Map<AlertType, AlertStats> stats = new ConcurrentHashMap<>();

        alertQueues.keySet().forEach(alertType -> {
            stats.put(alertType, AlertStats.builder()
                    .alertType(alertType)
                    .queueSize(getQueueSize(alertType))
                    .totalProcessed(getTotalAlertsCount(alertType))
                    .build());
        });

        return stats;
    }

    /**
     * Clear specific alert queue (for testing/admin)
     */
    public void clearQueue(AlertType alertType) {
        Queue<AlertEntry> queue = alertQueues.get(alertType);
        Set<String> tracking = alertTracking.get(alertType);

        if (queue != null) {
            int cleared = queue.size();
            queue.clear();
            log.info("Cleared {} alerts from {} queue", cleared, alertType);
        }

        if (tracking != null) {
            tracking.clear();
        }
    }

    /**
     * Clear all alert queues
     */
    public void clearAllQueues() {
        alertQueues.keySet().forEach(this::clearQueue);
    }

    /**
     * Alert entry data structure
     */
    @Data
    @Builder
    public static class AlertEntry {
        private AlertType alertType;
        private String symbol;
        private String timeframe;
        private double price;
        private String message;
        private Map<String, Object> metadata;
        private LocalDateTime timestamp;
        private String alertKey;
    }

    /**
     * Alert statistics
     */
    @Data
    @Builder
    public static class AlertStats {
        private AlertType alertType;
        private int queueSize;
        private int totalProcessed;
    }

    /**
     * Alert types enum - dynamically extensible
     */
    private void logOpenAIOutcome(ChartAnalysisResponse response, AlertEntry entry, List<Interval> tfList) {
        if (response == null) {
            log.warn("OpenAI analysis returned null for symbol {}", entry.getSymbol());
            return;
        }

        Object json = response.getJsonAnalysis();
        String text = response.getAnalysis();

        if (json != null) {
            log.info("OpenAI JSON analysis for {} [base={}, family={}]: {}", 
                    entry.getSymbol(), entry.getTimeframe(), tfList, json);
        } else if (text != null && !text.isBlank()) {
            log.info("OpenAI text analysis for {} [base={}, family={}]: {}",
                    entry.getSymbol(), entry.getTimeframe(), tfList, text);
        } else {
            log.warn("OpenAI analysis empty for {} [base={}, family={}]", 
                    entry.getSymbol(), entry.getTimeframe(), tfList);
        }
    }

    public enum AlertType {
        SWING_BUY("Swing Buy"),
        SWING_SELL("Swing Sell"), 
        ASTA_BUY("ASTA Buy"),
        ASTA_SELL("ASTA Sell"),

        // Bollinger Band specific alerts
        BOLLINGER_BAND_CHALLENGED("Bollinger Band Challenged"),
        BOLLINGER_SHRINKING_DOWN("Bollinger Shrinking from Down"),
        BOLLINGER_SHRINKING_UP("Bollinger Shrinking from Up"),

        // Additional alert types can be added here
        BREAKOUT_UP("Breakout Up"),
        BREAKOUT_DOWN("Breakout Down"),
        TREND_CHANGE("Trend Change"),
        VOLUME_SPIKE("Volume Spike");

        private final String displayName;

        AlertType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
