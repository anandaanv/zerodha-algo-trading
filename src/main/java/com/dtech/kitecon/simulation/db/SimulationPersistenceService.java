package com.dtech.kitecon.simulation.db;

import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Service for persisting and retrieving simulation results to/from the database.
 * Handles serialization of complex objects (patternPivots, triggerMeta) to JSON strings.
 */
@Service
@Slf4j
@Transactional
public class SimulationPersistenceService {

    @Autowired
    private SimulationRunRepository runRepo;

    @Autowired
    private SimulationTradeRepository tradeRepo;

    @Autowired
    private CandleRepository candleRepo;

    @Autowired
    private InstrumentRepository instrRepo;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Persist a complete simulation run with all trades.
     *
     * @param runId       Unique identifier for this run
     * @param strategyName Description of the strategy
     * @param timeframe   e.g. "OneHour"
     * @param stocksCount Number of stocks processed
     * @param trades      List of TradeRecord objects or Maps to persist
     * @param extraMeta   Additional metadata to store as JSON
     * @return The persisted SimulationRun entity
     */
    public SimulationRun persistRun(String runId, String strategyName, String timeframe,
                                     int stocksCount, List<?> trades,
                                     Map<String, Object> extraMeta) {
        try {
            // Calculate totals from trades
            int wins = 0;
            int losses = 0;
            double totalPnl = 0;

            for (Object tradeObj : trades) {
                boolean wasWinner;
                double pnlPct;

                if (tradeObj instanceof Map) {
                    // Map-based trade object
                    Map<String, Object> map = (Map<String, Object>) tradeObj;
                    wasWinner = (Boolean) map.get("was_winner");
                    pnlPct = ((Number) map.get("pnl_pct")).doubleValue();
                } else {
                    // Use reflection to access trade fields (since TradeRecord is in test package)
                    wasWinner = (boolean) getFieldValue(tradeObj, "wasWinner");
                    pnlPct = (double) getFieldValue(tradeObj, "pnlPct");
                }

                if (wasWinner) wins++; else losses++;
                totalPnl += pnlPct;
            }

            // Serialize extraMeta to JSON
            String extraMetaJson = objectMapper.writeValueAsString(extraMeta);

            // Create and save SimulationRun
            SimulationRun run = new SimulationRun();
            run.setRunId(runId);
            run.setStrategyName(strategyName);
            run.setTimeframe(timeframe);
            run.setStocksCount(stocksCount);
            run.setTotalTrades(trades.size());
            run.setWins(wins);
            run.setLosses(losses);
            run.setTotalPnlPct(totalPnl);
            run.setExtraMeta(extraMetaJson);

            SimulationRun savedRun = runRepo.save(run);
            log.info("Saved SimulationRun: id={}, runId={}, trades={}", savedRun.getId(), runId, trades.size());

            // Persist each trade
            for (Object tradeObj : trades) {
                SimulationTrade trade = mapTradeRecordToEntity(tradeObj, savedRun);
                tradeRepo.save(trade);
            }

            log.info("Persisted {} trades for run {}", trades.size(), runId);
            return savedRun;

        } catch (Exception e) {
            log.error("Error persisting run {}: {}", runId, e.getMessage(), e);
            throw new RuntimeException("Failed to persist simulation run", e);
        }
    }

    /**
     * Get all simulation runs ordered by creation date (newest first).
     */
    public List<SimulationRun> findAllRuns() {
        return runRepo.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Find a simulation run by its unique runId.
     */
    public Optional<SimulationRun> findByRunId(String runId) {
        return runRepo.findByRunId(runId);
    }

    /**
     * Get paginated trades for a run.
     */
    public Page<SimulationTrade> getTrades(Long runId, Pageable pageable) {
        return tradeRepo.findByRunIdOrderByEntryTime(runId, pageable);
    }

    /**
     * Get a single trade by ID and runId (security check).
     */
    public Optional<SimulationTrade> getTrade(Long runId, Long tradeId) {
        return tradeRepo.findByIdAndRunId(tradeId, runId);
    }

    /**
     * Get total trade count for a run.
     */
    public long getTradeCount(Long runId) {
        return tradeRepo.countByRunId(runId);
    }

    /**
     * Get bars around trade entry/exit from the Candle table.
     * Queries a time window and returns bars sorted by timestamp.
     */
    public List<Map<String, Object>> getBarsAround(String symbol, Instant startTime, Instant endTime) {
        try {
            // Look up instrument by trading symbol
            var instruments = instrRepo.findAllByTradingsymbol(symbol);
            if (instruments == null || instruments.isEmpty()) {
                log.warn("No instrument found for symbol: {}", symbol);
                return new ArrayList<>();
            }

            var instrument = instruments.get(0);

            // Query candles from Candle table for this instrument, hourly timeframe, within time window
            var candles = candleRepo.findAllByInstrumentAndTimeframeAndTimestampBetween(
                    instrument,
                    com.dtech.algo.series.Interval.OneHour,
                    startTime,
                    endTime
            );

            // Convert to Map with bar_index, timestamp, OHLCV
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < candles.size(); i++) {
                Candle candle = candles.get(i);
                Map<String, Object> bar = new LinkedHashMap<>();
                bar.put("bar_index", i);
                bar.put("timestamp", candle.getTimestamp());
                bar.put("open", candle.getOpen());
                bar.put("high", candle.getHigh());
                bar.put("low", candle.getLow());
                bar.put("close", candle.getClose());
                bar.put("volume", candle.getVolume());
                result.add(bar);
            }

            return result;

        } catch (Exception e) {
            log.error("Error fetching bars for symbol {} between {} and {}", symbol, startTime, endTime, e);
            return new ArrayList<>();
        }
    }

    /**
     * Map a TradeRecord (from test package) or a Map to SimulationTrade JPA entity.
     * Supports both reflection-based access for TradeRecord objects and direct Map access for JSON backfill.
     */
    private SimulationTrade mapTradeRecordToEntity(Object tradeObj, SimulationRun run) {
        try {
            SimulationTrade trade = new SimulationTrade();
            trade.setRun(run);

            if (tradeObj instanceof Map) {
                // Map-based approach (from JSON backfill)
                Map<String, Object> map = (Map<String, Object>) tradeObj;

                trade.setSymbol((String) map.get("symbol"));
                trade.setPatternType((String) map.get("pattern_type"));
                trade.setDirection((String) map.get("direction"));
                trade.setEntryBar(((Number) map.get("entry_bar")).intValue());
                trade.setEntryTime(Instant.parse((String) map.get("entry_time")));
                trade.setEntryPrice(((Number) map.get("entry_price")).doubleValue());
                trade.setStopInitial(((Number) map.get("stop_initial")).doubleValue());
                trade.setTargetInitial(((Number) map.get("target_initial")).doubleValue());
                trade.setExitBar(((Number) map.get("exit_bar")).intValue());
                trade.setExitTime(Instant.parse((String) map.get("exit_time")));
                trade.setExitPrice(((Number) map.get("exit_price")).doubleValue());
                trade.setExitReason((String) map.get("exit_reason"));
                trade.setPnlPct(((Number) map.get("pnl_pct")).doubleValue());
                trade.setWasWinner((Boolean) map.get("was_winner"));
                trade.setHoldingBars(((Number) map.get("holding_bars")).intValue());

                // Serialize patternPivots list to JSON
                Object patternPivotsObj = map.get("pattern_pivots");
                if (patternPivotsObj != null) {
                    String pivotsJson = objectMapper.writeValueAsString(patternPivotsObj);
                    trade.setPatternPivots(pivotsJson);
                }

                // Build and serialize triggerMeta
                Map<String, Object> triggerMeta = new LinkedHashMap<>();
                triggerMeta.put("trigger_macd_cross_date_daily", map.get("trigger_macd_cross_date_daily"));
                triggerMeta.put("trigger_stochrsi_sat_time_hourly", map.get("trigger_stochrsi_sat_time_hourly"));
                triggerMeta.put("hourly_bars_from_trigger_to_candle", map.get("hourly_bars_from_trigger_to_candle"));
                triggerMeta.put("hourly_bars_from_candle_to_entry", map.get("hourly_bars_from_candle_to_entry"));

                String triggerMetaJson = objectMapper.writeValueAsString(triggerMeta);
                trade.setTriggerMeta(triggerMetaJson);

            } else {
                // Reflection-based approach (original TradeRecord objects from tests)
                trade.setSymbol((String) getFieldValue(tradeObj, "symbol"));
                trade.setPatternType((String) getFieldValue(tradeObj, "patternType"));
                trade.setDirection("SHORT");
                trade.setEntryBar((int) getFieldValue(tradeObj, "entryBar"));
                trade.setEntryTime((Instant) getFieldValue(tradeObj, "entryTime"));
                trade.setEntryPrice((double) getFieldValue(tradeObj, "entryPrice"));
                trade.setStopInitial((double) getFieldValue(tradeObj, "stopInitial"));
                trade.setTargetInitial((double) getFieldValue(tradeObj, "targetInitial"));
                trade.setExitBar((int) getFieldValue(tradeObj, "exitBar"));
                trade.setExitTime((Instant) getFieldValue(tradeObj, "exitTime"));
                trade.setExitPrice((double) getFieldValue(tradeObj, "exitPrice"));
                trade.setExitReason((String) getFieldValue(tradeObj, "exitReason"));
                trade.setPnlPct((double) getFieldValue(tradeObj, "pnlPct"));
                trade.setWasWinner((boolean) getFieldValue(tradeObj, "wasWinner"));
                trade.setHoldingBars((int) getFieldValue(tradeObj, "holdingBars"));

                // Serialize patternPivots list to JSON
                Object patternPivotsObj = getFieldValue(tradeObj, "patternPivots");
                if (patternPivotsObj != null) {
                    String pivotsJson = objectMapper.writeValueAsString(patternPivotsObj);
                    trade.setPatternPivots(pivotsJson);
                }

                // Build and serialize triggerMeta
                Map<String, Object> triggerMeta = new LinkedHashMap<>();
                triggerMeta.put("trigger_macd_cross_date_daily",
                        getFieldValue(tradeObj, "triggerMacdCrossDate"));
                triggerMeta.put("trigger_stochrsi_sat_time_hourly",
                        getFieldValue(tradeObj, "triggerStochRsiTime"));
                triggerMeta.put("hourly_bars_from_trigger_to_candle",
                        getFieldValue(tradeObj, "hoursFromTriggerToCandle"));
                triggerMeta.put("hourly_bars_from_candle_to_entry",
                        getFieldValue(tradeObj, "hoursFromCandleToEntry"));

                String triggerMetaJson = objectMapper.writeValueAsString(triggerMeta);
                trade.setTriggerMeta(triggerMetaJson);
            }

            return trade;

        } catch (Exception e) {
            log.error("Error mapping TradeRecord to entity: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to map trade record", e);
        }
    }

    /**
     * Utility to access private fields via reflection.
     */
    private Object getFieldValue(Object obj, String fieldName) throws Exception {
        var field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(obj);
    }
}
