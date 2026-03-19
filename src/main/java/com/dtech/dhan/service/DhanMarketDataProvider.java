package com.dtech.dhan.service;

import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.dhan.client.DhanApiClient;
import com.dtech.dhan.config.DhanConnectConfig;
import com.dtech.dhan.model.DhanHistoricalData;
import com.dtech.dhan.model.DhanQuote;
import com.dtech.kitecon.service.dataprovider.MarketDataProvider;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Quote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dhan API-backed market data provider
 * Implements MarketDataProvider interface for Dhan broker integration
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "dhan.enabled", havingValue = "true", matchIfMissing = false)
public class DhanMarketDataProvider implements MarketDataProvider {

    private final DhanConnectConfig dhanConnectConfig;
    private final DhanApiClient dhanApiClient;

    @Override
    public String getProviderName() {
        return "dhan";
    }

    @Override
    public boolean isAvailable() {
        try {
            return dhanConnectConfig.isConfigured();
        } catch (Exception e) {
            log.warn("Dhan provider not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public int getPriority() {
        return 15; // Higher than Zerodha (10), lower than database (20)
    }

    @Override
    public IntervalBarSeries loadBarSeries(BarSeriesConfig config) throws Exception {
        // TODO: Implement bar series loading from Dhan
        // This will be implemented in DhanBarSeriesLoader
        throw new UnsupportedOperationException("Dhan provider direct bar series loading not yet implemented. Use DhanBarSeriesLoader.");
    }

    @Override
    public Quote getQuote(String symbol) throws Exception {
        try {
            if (!dhanConnectConfig.isConfigured()) {
                throw new IllegalStateException("Dhan API not configured");
            }

            // TODO: Implement symbol to Dhan securityId mapping
            // For now, assume symbol contains securityId
            String securityId = symbol;
            String exchangeSegment = "NSE_EQ"; // Default to NSE equity

            DhanQuote dhanQuote = dhanApiClient.getQuote(
                securityId,
                exchangeSegment,
                dhanConnectConfig.getAccessToken()
            );

            // Convert DhanQuote to Zerodha Quote format for compatibility
            return convertToZerodhaQuote(dhanQuote, symbol);
        } catch (Exception e) {
            log.error("Failed to get quote from Dhan for symbol: {}", symbol, e);
            throw new Exception("Failed to get quote from Dhan: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Quote> getQuotes(List<String> symbols) throws Exception {
        try {
            if (!dhanConnectConfig.isConfigured()) {
                throw new IllegalStateException("Dhan API not configured");
            }

            Map<String, Quote> quotes = new HashMap<>();

            // Dhan doesn't have batch quote API, so fetch individually
            // TODO: Consider using getLTP for batch and then getQuote for detailed data
            for (String symbol : symbols) {
                try {
                    Quote quote = getQuote(symbol);
                    quotes.put(symbol, quote);
                } catch (Exception e) {
                    log.warn("Failed to get quote for symbol {}: {}", symbol, e.getMessage());
                }
            }

            return quotes;
        } catch (Exception e) {
            log.error("Failed to get quotes from Dhan", e);
            throw new Exception("Failed to get quotes from Dhan: " + e.getMessage(), e);
        }
    }

    @Override
    public List<HistoricalData> getHistoricalData(
            Long instrumentToken,
            LocalDate from,
            LocalDate to,
            String interval,
            boolean continuous
    ) throws Exception {
        try {
            if (!dhanConnectConfig.isConfigured()) {
                throw new IllegalStateException("Dhan API not configured");
            }

            // TODO: Implement instrumentToken to Dhan securityId mapping
            String securityId = String.valueOf(instrumentToken);
            String exchangeSegment = "NSE_EQ";
            String instrument = "EQUITY";

            List<DhanHistoricalData> dhanData;

            // Determine if daily or intraday based on interval
            if (interval.equals("day") || interval.equals("1d")) {
                dhanData = dhanApiClient.getHistoricalDaily(
                    securityId,
                    exchangeSegment,
                    instrument,
                    from,
                    to,
                    dhanConnectConfig.getAccessToken()
                );
            } else {
                // Parse interval to minutes (e.g., "5minute" -> 5)
                int intervalMinutes = parseIntervalToMinutes(interval);
                dhanData = dhanApiClient.getHistoricalIntraday(
                    securityId,
                    exchangeSegment,
                    instrument,
                    intervalMinutes,
                    from,
                    to,
                    dhanConnectConfig.getAccessToken()
                );
            }

            // Convert to Zerodha HistoricalData format for compatibility
            return convertToZerodhaHistoricalData(dhanData);
        } catch (Exception e) {
            log.error("Failed to get historical data from Dhan", e);
            throw new Exception("Failed to get historical data from Dhan: " + e.getMessage(), e);
        }
    }

    /**
     * Convert Dhan quote to Zerodha quote format for compatibility
     */
    private Quote convertToZerodhaQuote(DhanQuote dhanQuote, String symbol) {
        Quote quote = new Quote();

        // Map Dhan fields to Zerodha Quote fields
        quote.lastPrice = dhanQuote.getLastTradedPrice();
        quote.lastTradedQuantity = dhanQuote.getLastTradedQuantity().longValue();
        quote.volume = dhanQuote.getVolume();
        quote.ohlc = new Quote.OHLC();
        quote.ohlc.open = dhanQuote.getOpen();
        quote.ohlc.high = dhanQuote.getHigh();
        quote.ohlc.low = dhanQuote.getLow();
        quote.ohlc.close = dhanQuote.getClose();

        // Depth data
        if (dhanQuote.getBidPrice() != null && dhanQuote.getAskPrice() != null) {
            quote.depth = new Quote.Depth();
            quote.depth.buy = new Quote.Depth.MarketDepth[1];
            quote.depth.sell = new Quote.Depth.MarketDepth[1];

            Quote.Depth.MarketDepth bid = new Quote.Depth.MarketDepth();
            bid.price = dhanQuote.getBidPrice();
            bid.quantity = dhanQuote.getBidQuantity().intValue();
            bid.orders = 1;
            quote.depth.buy[0] = bid;

            Quote.Depth.MarketDepth ask = new Quote.Depth.MarketDepth();
            ask.price = dhanQuote.getAskPrice();
            ask.quantity = dhanQuote.getAskQuantity().intValue();
            ask.orders = 1;
            quote.depth.sell[0] = ask;
        }

        return quote;
    }

    /**
     * Convert Dhan historical data to Zerodha format
     */
    private List<HistoricalData> convertToZerodhaHistoricalData(List<DhanHistoricalData> dhanData) {
        List<HistoricalData> result = new ArrayList<>();

        for (DhanHistoricalData dhan : dhanData) {
            HistoricalData hd = new HistoricalData();
            // Parse timestamp string to Date
            // Dhan timestamp format: "2024-01-01T00:00:00" or similar
            try {
                hd.timeStamp = java.sql.Timestamp.valueOf(dhan.getTimestamp().replace("T", " "));
            } catch (Exception e) {
                log.warn("Failed to parse timestamp: {}", dhan.getTimestamp());
                continue;
            }

            hd.open = dhan.getOpen();
            hd.high = dhan.getHigh();
            hd.low = dhan.getLow();
            hd.close = dhan.getClose();
            hd.volume = dhan.getVolume();
            hd.oi = dhan.getOpenInterest();

            result.add(hd);
        }

        return result;
    }

    /**
     * Parse interval string to minutes
     * Examples: "5minute" -> 5, "60minute" -> 60, "1d" -> throw exception
     */
    private int parseIntervalToMinutes(String interval) {
        if (interval.contains("minute")) {
            return Integer.parseInt(interval.replace("minute", ""));
        }

        // Map common intervals
        switch (interval) {
            case "1":
            case "1m":
                return 1;
            case "5":
            case "5m":
                return 5;
            case "15":
            case "15m":
                return 15;
            case "60":
            case "1h":
                return 60;
            default:
                throw new IllegalArgumentException("Unsupported interval: " + interval);
        }
    }
}
