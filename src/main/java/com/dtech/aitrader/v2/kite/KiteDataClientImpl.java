package com.dtech.aitrader.v2.kite;

import com.dtech.kitecon.config.KiteConnectPool;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Holding;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Margin;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Position;
import com.zerodhatech.models.Quote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Read-only Kite client used by the AI Trader v2 orchestrator and trigger.
 *
 * Backed by the existing {@link KiteConnectPool} — uses the primary authenticated
 * client for state queries and the round-robin historical client for OHLC fetches
 * (to spread Kite's per-app rate-limit across users when multi-tenant).
 *
 * Order placement is intentionally NOT exposed here. Live orders continue to flow
 * through the existing algotrade OrderService path per 2026-05-18 design correction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KiteDataClientImpl implements KiteDataClient {

    /**
     * Kite's historical endpoint expects "YYYY-MM-DD HH:mm:ss". For daily/weekly we send
     * midnight; intra-day fetches will be added when the orchestrator needs them.
     */
    private static final DateTimeFormatter KITE_HISTORICAL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KiteConnectPool pool;

    @Override
    public Map<String, Quote> getQuote(Long userId, List<String> instruments) {
        try {
            KiteConnect kc = primary();
            String[] arr = instruments.toArray(new String[0]);
            return kc.getQuote(arr);
        } catch (KiteException | java.io.IOException e) {
            throw new KiteDataException("getQuote failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, LTPQuote> getLtp(Long userId, List<String> instruments) {
        try {
            KiteConnect kc = primary();
            String[] arr = instruments.toArray(new String[0]);
            return kc.getLTP(arr);
        } catch (KiteException | java.io.IOException e) {
            throw new KiteDataException("getLtp failed: " + e.getMessage(), e);
        }
    }

    @Override
    public HistoricalData getHistoricalData(Long userId,
                                            long instrumentToken,
                                            LocalDate from,
                                            LocalDate to,
                                            String interval,
                                            boolean continuous,
                                            boolean oi) {
        try {
            KiteConnect kc = historical();
            // SDK uses java.util.Date — wrap LocalDate at start-of-day.
            java.util.Date fromDate = java.sql.Timestamp.valueOf(from.atStartOfDay());
            java.util.Date toDate = java.sql.Timestamp.valueOf(to.atTime(23, 59, 59));
            return kc.getHistoricalData(fromDate, toDate, String.valueOf(instrumentToken), interval, continuous, oi);
        } catch (KiteException | java.io.IOException e) {
            throw new KiteDataException("getHistoricalData failed for " + instrumentToken + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<Holding> getHoldings(Long userId) {
        try {
            return primary().getHoldings();
        } catch (KiteException | java.io.IOException e) {
            throw new KiteDataException("getHoldings failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, List<Position>> getPositions(Long userId) {
        try {
            // KiteConnect 4.x getPositions() returns Map<String, List<Position>>
            // with keys "day" and "net" — surface as-is.
            return primary().getPositions();
        } catch (KiteException | java.io.IOException e) {
            throw new KiteDataException("getPositions failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Order> getOrders(Long userId) {
        try {
            return primary().getOrders();
        } catch (KiteException | java.io.IOException e) {
            throw new KiteDataException("getOrders failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Margin> getMargins(Long userId) {
        try {
            return primary().getMargins();
        } catch (KiteException | java.io.IOException e) {
            throw new KiteDataException("getMargins failed: " + e.getMessage(), e);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private KiteConnect primary() {
        KiteConnect kc = pool.getPrimaryClient();
        if (kc == null) {
            throw new KiteDataException("no primary KiteConnect client available — login required");
        }
        return kc;
    }

    private KiteConnect historical() {
        KiteConnect kc = pool.getNextClientForHistorical();
        if (kc == null) {
            throw new KiteDataException("no authenticated KiteConnect client available for historical data");
        }
        return kc;
    }
}
