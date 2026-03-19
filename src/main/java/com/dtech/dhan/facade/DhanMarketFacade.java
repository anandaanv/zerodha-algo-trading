package com.dtech.dhan.facade;

import com.dtech.dhan.client.DhanApiClient;
import com.dtech.dhan.dto.*;
import com.dtech.kitecon.market.facade.MarketException;
import com.dtech.kitecon.market.facade.MarketFacade;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Profile;
import com.zerodhatech.models.Quote;
import com.zerodhatech.models.Trade;
import com.zerodhatech.models.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dhan implementation of MarketFacade
 * Converts between Dhan DTOs and Zerodha models for compatibility
 *
 * NOT a Spring bean - instantiated by MarketFacadeProvider
 */
@RequiredArgsConstructor
@Slf4j
public class DhanMarketFacade implements MarketFacade {

    private final DhanApiClient dhanApiClient;
    private final String accessToken;
    private final String clientId;

    @Override
    public String getBrokerName() {
        return "dhan";
    }

    @Override
    public boolean isAvailable() {
        return accessToken != null && !accessToken.trim().isEmpty();
    }

    @Override
    public String getAccessToken() {
        return accessToken;
    }

    // ==================== User Profile ====================

    @Override
    public Profile getProfile() throws MarketException {
        // Dhan doesn't have a profile API endpoint
        // Return a basic profile with broker name
        Profile profile = new Profile();
        profile.userName = "Dhan User";
        profile.broker = "dhan";
        return profile;
    }

    // ==================== Market Data ====================

    @Override
    public HistoricalData getHistoricalData(
        Date from,
        Date to,
        String token,
        String interval,
        boolean continuous,
        boolean oi
    ) throws MarketException {
        try {
            LocalDate fromDate = from.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDate toDate = to.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();

            DhanHistoricalResponse response;

            // Determine if daily or intraday
            if ("day".equals(interval) || "1d".equals(interval)) {
                response = dhanApiClient.getHistoricalDaily(
                    token, "NSE_EQ", "EQUITY", fromDate, toDate, accessToken, clientId
                );
            } else {
                int intervalMinutes = parseIntervalToMinutes(interval);
                response = dhanApiClient.getHistoricalIntraday(
                    token, "NSE_EQ", "EQUITY", intervalMinutes, fromDate, toDate, accessToken, clientId
                );
            }

            return convertToZerodhaHistoricalData(response);
        } catch (Exception e) {
            throw new MarketException("dhan", "HISTORICAL_ERROR",
                "Failed to fetch historical data: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Quote> getQuote(String[] instruments) throws MarketException {
        try {
            // Parse instruments format "EXCHANGE:SYMBOL" or just security ID
            List<DhanQuoteRequest.InstrumentIdentifier> dhanInstruments =
                Arrays.stream(instruments)
                    .map(this::parseInstrumentString)
                    .collect(Collectors.toList());

            List<DhanQuoteResponse.QuoteData> quotes =
                dhanApiClient.getQuotes(dhanInstruments, accessToken, clientId);

            // Convert to Zerodha Quote format
            Map<String, Quote> result = new HashMap<>();
            for (int i = 0; i < instruments.length && i < quotes.size(); i++) {
                result.put(instruments[i], convertToZerodhaQuote(quotes.get(i)));
            }
            return result;
        } catch (Exception e) {
            throw new MarketException("dhan", "QUOTE_ERROR",
                "Failed to fetch quotes: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, LTPQuote> getLTP(String[] instruments) throws MarketException {
        try {
            List<DhanLTPRequest.InstrumentIdentifier> dhanInstruments =
                Arrays.stream(instruments)
                    .map(this::parseInstrumentString)
                    .map(id -> DhanLTPRequest.InstrumentIdentifier.builder()
                        .securityId(id.getSecurityId())
                        .exchangeSegment(id.getExchangeSegment())
                        .build())
                    .collect(Collectors.toList());

            List<DhanLTPResponse.LTPData> ltpData =
                dhanApiClient.getLTP(dhanInstruments, accessToken, clientId);

            Map<String, LTPQuote> result = new HashMap<>();
            for (int i = 0; i < instruments.length && i < ltpData.size(); i++) {
                LTPQuote quote = new LTPQuote();
                quote.lastPrice = ltpData.get(i).getLastPrice();
                quote.instrumentToken = Long.parseLong(ltpData.get(i).getSecurityId());
                result.put(instruments[i], quote);
            }
            return result;
        } catch (Exception e) {
            throw new MarketException("dhan", "LTP_ERROR",
                "Failed to fetch LTP: " + e.getMessage(), e);
        }
    }

    // ==================== Instruments ====================

    @Override
    public List<Instrument> getInstruments() throws MarketException {
        // Dhan doesn't have a get-all-instruments API
        // Would need to download from their website or use a mapping service
        throw new MarketException("dhan", "NOT_IMPLEMENTED",
            "Dhan doesn't provide a get-all-instruments API. Use instrument mapping service.", null);
    }

    // ==================== Orders ====================

    @Override
    public Order placeOrder(OrderParams params, String variety) throws MarketException {
        // TODO: Implement Dhan order placement
        throw new MarketException("dhan", "NOT_IMPLEMENTED",
            "Dhan order placement not yet implemented", null);
    }

    @Override
    public List<Trade> getOrderTrades(String orderId) throws MarketException {
        // TODO: Implement Dhan trade fetching
        throw new MarketException("dhan", "NOT_IMPLEMENTED",
            "Dhan trade fetching not yet implemented", null);
    }

    // ==================== Session Management ====================

    @Override
    public User generateSession(String requestToken, String apiSecret) throws MarketException {
        throw new MarketException("dhan", "NOT_SUPPORTED",
            "Dhan uses direct access tokens, not OAuth session generation", null);
    }

    @Override
    public String getLoginURL() throws MarketException {
        throw new MarketException("dhan", "NOT_SUPPORTED",
            "Dhan uses direct access tokens, not OAuth login URL", null);
    }

    @Override
    public void setAccessToken(String accessToken) {
        // Dhan token is immutable in facade - must create new facade
        log.warn("Cannot set access token on existing Dhan facade. Create new facade instance.");
    }

    @Override
    public void setPublicToken(String publicToken) {
        // Not applicable for Dhan
    }

    @Override
    public void setUserId(String userId) {
        // Not applicable for Dhan
    }

    // ==================== Helper Methods ====================

    /**
     * Parse instrument string to Dhan identifier
     * Formats: "NSE:SYMBOL", "SECURITY_ID", "NSE_EQ:1333"
     */
    private DhanQuoteRequest.InstrumentIdentifier parseInstrumentString(String instrument) {
        // TODO: Implement proper instrument mapping
        // For now, assume format "SECURITY_ID" or "NSE_EQ:SECURITY_ID"
        if (instrument.contains(":")) {
            String[] parts = instrument.split(":");
            return DhanQuoteRequest.InstrumentIdentifier.builder()
                .exchangeSegment(parts[0])
                .securityId(parts.length > 1 ? parts[1] : parts[0])
                .build();
        }
        return DhanQuoteRequest.InstrumentIdentifier.builder()
            .exchangeSegment("NSE_EQ")
            .securityId(instrument)
            .build();
    }

    /**
     * Convert Dhan quote to Zerodha quote format
     *
     * Note: Limited conversion due to Zerodha SDK version constraints.
     * The Quote class structure in kiteconnect 3.5.1 doesn't expose
     * nested class constructors, so only basic fields are populated.
     */
    private Quote convertToZerodhaQuote(DhanQuoteResponse.QuoteData dhanQuote) {
        Quote quote = new Quote();

        // Basic price data
        quote.lastPrice = dhanQuote.getLastPrice();

        // Note: OHLC, volume, depth fields cannot be directly populated
        // due to Zerodha SDK constraints in version 3.5.1.
        // The Quote object from Kite API already has these populated,
        // but we cannot construct them manually.
        //
        // For full market data, consider using ZerodhaMarketFacade instead,
        // or update to a newer Zerodha SDK version that exposes these constructors.

        return quote;
    }

    /**
     * Convert Dhan historical response to Zerodha format
     */
    private HistoricalData convertToZerodhaHistoricalData(DhanHistoricalResponse response) {
        HistoricalData result = new HistoricalData();
        result.dataArrayList = new ArrayList<>();

        if (response.getData() != null) {
            for (DhanHistoricalResponse.HistoricalCandle candle : response.getData()) {
                HistoricalData data = new HistoricalData();
                data.timeStamp = candle.getTimestamp();
                data.open = candle.getOpen();
                data.high = candle.getHigh();
                data.low = candle.getLow();
                data.close = candle.getClose();
                data.volume = candle.getVolume();
                data.oi = candle.getOpenInterest();
                result.dataArrayList.add(data);
            }
        }

        return result;
    }

    /**
     * Parse interval string to minutes
     */
    private int parseIntervalToMinutes(String interval) {
        if (interval.contains("minute")) {
            return Integer.parseInt(interval.replace("minute", ""));
        }
        switch (interval) {
            case "1": case "1m": return 1;
            case "5": case "5m": return 5;
            case "15": case "15m": return 15;
            case "25": case "25m": return 25;
            case "60": case "1h": return 60;
            default:
                log.warn("Unknown interval: {}, defaulting to 5", interval);
                return 5;
        }
    }
}
