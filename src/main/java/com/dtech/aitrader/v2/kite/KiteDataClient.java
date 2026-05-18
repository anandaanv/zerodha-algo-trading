package com.dtech.aitrader.v2.kite;

import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Holding;
import com.zerodhatech.models.Position;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Margin;
import com.zerodhatech.models.Quote;
import com.zerodhatech.models.LTPQuote;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * READ-ONLY view over Kite for AI Trader v2.
 *
 * Per the 2026-05-18 correction (memsys memory 1add3fb3-…) order placement
 * is NOT routed through this client. Live orders continue to use the existing
 * algotrade OrderService path. This client exposes only the read methods
 * Agent 1's input bundle and the deterministic trigger need.
 *
 * Implementation wraps the existing KiteConnectPool (per-user KiteConnect
 * instances with current access tokens) — the same path KiteAutoLoginService
 * uses.
 */
public interface KiteDataClient {

    /** Latest LTP + bid/ask depth for one or more exchange-prefixed instruments. */
    Map<String, Quote> getQuote(Long userId, List<String> instruments) throws KiteDataException;

    /** Quick LTP-only fetch — cheaper than full quote when only the price is needed. */
    Map<String, LTPQuote> getLtp(Long userId, List<String> instruments) throws KiteDataException;

    /**
     * Historical OHLC bars for one instrument. Interval values match Kite's API
     * ("minute", "3minute", "5minute", "15minute", "30minute", "60minute", "day", "week", "month").
     */
    HistoricalData getHistoricalData(
            Long userId,
            long instrumentToken,
            LocalDate from,
            LocalDate to,
            String interval,
            boolean continuous,
            boolean oi
    ) throws KiteDataException;

    /** Current holdings. Used by morning chat review for portfolio context. */
    List<Holding> getHoldings(Long userId) throws KiteDataException;

    /** Current intraday + overnight positions. */
    Map<String, List<Position>> getPositions(Long userId) throws KiteDataException;

    /** Today's orders (placed, executed, cancelled). */
    List<Order> getOrders(Long userId) throws KiteDataException;

    /** Margin balances per segment. */
    Map<String, Margin> getMargins(Long userId) throws KiteDataException;
}
