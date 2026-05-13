package com.dtech.kitecon.simulation.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-shot backfill test to move simulation results from JSON into the DB.
 * Run with: ./gradlew test --tests com.dtech.kitecon.simulation.db.BackfillSimulationFromJsonTest
 *
 * This test bypasses Spring framework since the application context has missing bean dependencies.
 * Instead, it connects directly to MySQL and inserts records using plain SQL and direct JDBC.
 */
@Disabled // run on demand only
class BackfillSimulationFromJsonTest {

    private static final String JDBC_URL = "jdbc:mysql://localhost:3306/algotrading";
    private static final String DB_USER = "anand";
    private static final String DB_PASS = "password";
    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";

    @Test
    void backfillCandleShortRun() throws Exception {
        String runId = System.getProperty("backfill.run.id", "candle-short-20260512-141832");
        Path jsonPath = Path.of("/tmp/sim-results", runId + ".json");
        Assumptions.assumeTrue(Files.exists(jsonPath), "JSON file not found: " + jsonPath);

        // Load the JSON with streaming parser to avoid OOM on large files
        ObjectMapper mapper = new ObjectMapper();

        // First pass: parse metadata only
        String strategyName = null;
        String timeframe = null;
        int stocksCount = 0;

        try (com.fasterxml.jackson.core.JsonParser jp = mapper.getFactory().createParser(jsonPath.toFile())) {
            com.fasterxml.jackson.core.JsonToken token = jp.nextToken();
            while (token != null) {
                if (token == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                    String fieldName = jp.currentName();
                    jp.nextToken();

                    if ("strategy_name".equals(fieldName)) {
                        strategyName = jp.getValueAsString();
                    } else if ("timeframe".equals(fieldName)) {
                        timeframe = jp.getValueAsString();
                    } else if ("stocks_count".equals(fieldName)) {
                        stocksCount = jp.getValueAsInt();
                    } else if ("trades".equals(fieldName)) {
                        break;  // We'll parse trades separately
                    }
                }
                token = jp.nextToken();
            }
        }

        // Second pass: stream through trades array
        List<Map<String, Object>> tradeMaps = new ArrayList<>();
        try (com.fasterxml.jackson.core.JsonParser jp = mapper.getFactory().createParser(jsonPath.toFile())) {
            com.fasterxml.jackson.core.JsonToken token = jp.nextToken();
            while (token != null) {
                if (token == com.fasterxml.jackson.core.JsonToken.FIELD_NAME && "trades".equals(jp.currentName())) {
                    jp.nextToken();  // Enter the array
                    while ((token = jp.nextToken()) != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                        if (token == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                            Map<String, Object> trade = mapper.readValue(jp, new TypeReference<Map<String, Object>>() {});
                            tradeMaps.add(trade);
                        }
                    }
                    break;
                }
                token = jp.nextToken();
            }
        }

        System.out.println("Loaded JSON: strategy=" + strategyName + " timeframe=" + timeframe
                + " stocks=" + stocksCount + " trades=" + tradeMaps.size());

        // Connect to MySQL and perform backfill
        Class.forName(DRIVER);
        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS)) {
            conn.setAutoCommit(false);

            // Check if run already exists
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM simulation_run WHERE run_id = ?")) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        System.out.println("Run " + runId + " already in DB. Skipping.");
                        return;
                    }
                }
            }

            // Insert simulation_run
            long runPk = insertSimulationRun(conn, runId, strategyName, timeframe, stocksCount,
                    tradeMaps.size(), countWins(tradeMaps), countLosses(tradeMaps),
                    sumPnl(tradeMaps));

            // Insert trades
            long tradesInserted = 0;
            for (Map<String, Object> trade : tradeMaps) {
                insertSimulationTrade(conn, runPk, trade, mapper);
                tradesInserted++;
            }

            conn.commit();

            System.out.println("\nBackfilled run_id=" + runId + " run_pk=" + runPk
                    + " trades_inserted=" + tradesInserted + " expected=" + tradeMaps.size());

            // Verify
            long count = countTrades(conn, runPk);
            assert count == tradeMaps.size() : "Expected " + tradeMaps.size() + " trades, got " + count;
            System.out.println("Verification PASSED: " + count + " trades in DB");
        }
    }

    private long insertSimulationRun(Connection conn, String runId, String strategyName,
                                      String timeframe, int stocksCount, int totalTrades,
                                      int wins, int losses, double totalPnl) throws SQLException {
        String sql = "INSERT INTO simulation_run (run_id, strategy_name, timeframe, stocks_count, " +
                "total_trades, wins, losses, total_pnl_pct, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, runId);
            ps.setString(2, strategyName);
            ps.setString(3, timeframe);
            ps.setInt(4, stocksCount);
            ps.setInt(5, totalTrades);
            ps.setInt(6, wins);
            ps.setInt(7, losses);
            ps.setDouble(8, totalPnl);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertSimulationTrade(Connection conn, long runPk, Map<String, Object> trade,
                                        ObjectMapper mapper) throws SQLException, Exception {
        String sql = "INSERT INTO simulation_trade " +
                "(run_id_fk, symbol, pattern_type, direction, entry_bar, entry_time, entry_price, " +
                "stop_initial, target_initial, exit_bar, exit_time, exit_price, exit_reason, " +
                "pnl_pct, was_winner, holding_bars, pattern_pivots, trigger_meta) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, runPk);
            ps.setString(2, (String) trade.get("symbol"));
            ps.setString(3, (String) trade.get("pattern_type"));
            ps.setString(4, (String) trade.get("direction"));
            ps.setInt(5, ((Number) trade.get("entry_bar")).intValue());
            // Convert ISO timestamp to MySQL datetime
            ps.setString(6, isoToMySqlDateTime((String) trade.get("entry_time")));
            ps.setDouble(7, ((Number) trade.get("entry_price")).doubleValue());
            ps.setDouble(8, ((Number) trade.get("stop_initial")).doubleValue());
            ps.setDouble(9, ((Number) trade.get("target_initial")).doubleValue());
            ps.setInt(10, ((Number) trade.get("exit_bar")).intValue());
            // Convert ISO timestamp to MySQL datetime
            ps.setString(11, isoToMySqlDateTime((String) trade.get("exit_time")));
            ps.setDouble(12, ((Number) trade.get("exit_price")).doubleValue());
            ps.setString(13, (String) trade.get("exit_reason"));
            ps.setDouble(14, ((Number) trade.get("pnl_pct")).doubleValue());
            ps.setBoolean(15, (Boolean) trade.get("was_winner"));
            ps.setInt(16, ((Number) trade.get("holding_bars")).intValue());

            // Serialize pattern_pivots
            String pivots = mapper.writeValueAsString(trade.get("pattern_pivots"));
            ps.setString(17, pivots);

            // Build and serialize trigger_meta
            Map<String, Object> triggerMeta = new HashMap<>();
            triggerMeta.put("trigger_macd_cross_date_daily", trade.get("trigger_macd_cross_date_daily"));
            triggerMeta.put("trigger_stochrsi_sat_time_hourly", trade.get("trigger_stochrsi_sat_time_hourly"));
            triggerMeta.put("hourly_bars_from_trigger_to_candle", trade.get("hourly_bars_from_trigger_to_candle"));
            triggerMeta.put("hourly_bars_from_candle_to_entry", trade.get("hourly_bars_from_candle_to_entry"));
            String metaJson = mapper.writeValueAsString(triggerMeta);
            ps.setString(18, metaJson);

            ps.executeUpdate();
        }
    }

    private String isoToMySqlDateTime(String isoString) {
        if (isoString == null || isoString.isEmpty()) {
            return null;
        }
        // Convert "2017-09-27T04:45:00Z" to "2017-09-27 04:45:00"
        return isoString.replace("T", " ").replace("Z", "");
    }

    private long countTrades(Connection conn, long runPk) throws SQLException {
        String sql = "SELECT COUNT(*) FROM simulation_trade WHERE run_id_fk = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, runPk);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private int countWins(List<Map<String, Object>> trades) {
        return (int) trades.stream().filter(t -> (Boolean) t.get("was_winner")).count();
    }

    private int countLosses(List<Map<String, Object>> trades) {
        return (int) trades.stream().filter(t -> !(Boolean) t.get("was_winner")).count();
    }

    private double sumPnl(List<Map<String, Object>> trades) {
        return trades.stream()
                .mapToDouble(t -> ((Number) t.get("pnl_pct")).doubleValue())
                .sum();
    }
}
