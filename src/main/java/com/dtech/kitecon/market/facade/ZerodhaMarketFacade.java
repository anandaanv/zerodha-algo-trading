package com.dtech.kitecon.market.facade;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Zerodha Kite Connect implementation of MarketFacade.
 * Wraps KiteConnect SDK and converts exceptions to MarketException.
 *
 * This class is NOT a Spring bean - it's instantiated by MarketFacadeProvider.
 */
@RequiredArgsConstructor
@Slf4j
public class ZerodhaMarketFacade implements MarketFacade {

    private final KiteConnect kiteConnect;

    @Override
    public String getBrokerName() {
        return "zerodha";
    }

    @Override
    public boolean isAvailable() {
        try {
            return kiteConnect != null && kiteConnect.getAccessToken() != null;
        } catch (Exception e) {
            log.warn("Zerodha facade not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getAccessToken() {
        return kiteConnect.getAccessToken();
    }

    // ==================== User Profile ====================

    @Override
    public Profile getProfile() throws MarketException {
        try {
            return kiteConnect.getProfile();
        } catch (KiteException | IOException e) {
            throw new MarketException("zerodha", extractErrorCode(e), "Failed to get profile", e);
        }
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
            return kiteConnect.getHistoricalData(from, to, token, interval, continuous, oi);
        } catch (KiteException | IOException e) {
            throw new MarketException("zerodha", extractErrorCode(e),
                String.format("Failed to get historical data for token %s", token), e);
        }
    }

    @Override
    public Map<String, Quote> getQuote(String[] instruments) throws MarketException {
        try {
            return kiteConnect.getQuote(instruments);
        } catch (KiteException | IOException e) {
            throw new MarketException("zerodha", extractErrorCode(e),
                String.format("Failed to get quotes for %d instruments", instruments.length), e);
        }
    }

    @Override
    public Map<String, LTPQuote> getLTP(String[] instruments) throws MarketException {
        try {
            return kiteConnect.getLTP(instruments);
        } catch (KiteException | IOException e) {
            throw new MarketException("zerodha", extractErrorCode(e),
                String.format("Failed to get LTP for %d instruments", instruments.length), e);
        }
    }

    // ==================== Instruments ====================

    @Override
    public List<Instrument> getInstruments() throws MarketException {
        try {
            return kiteConnect.getInstruments();
        } catch (KiteException | IOException e) {
            throw new MarketException("zerodha", extractErrorCode(e), "Failed to get instruments", e);
        }
    }

    // ==================== Orders ====================

    @Override
    public Order placeOrder(OrderParams params, String variety) throws MarketException {
        try {
            return kiteConnect.placeOrder(params, variety);
        } catch (KiteException | IOException e) {
            log.error("[KiteOrder] FAILED exchange={} symbol={} txn={} qty={} price={} product={} orderType={} error={}",
                    params.exchange, params.tradingsymbol, params.transactionType,
                    params.quantity, params.price, params.product, params.orderType, e.getMessage());
            throw new MarketException("zerodha", extractErrorCode(e),
                String.format("Failed to place order for %s", params.tradingsymbol), e);
        }
    }

    @Override
    public List<Trade> getOrderTrades(String orderId) throws MarketException {
        try {
            return kiteConnect.getOrderTrades(orderId);
        } catch (KiteException | IOException e) {
            throw new MarketException("zerodha", extractErrorCode(e),
                String.format("Failed to get trades for order %s", orderId), e);
        }
    }

    // ==================== Session Management ====================

    @Override
    public User generateSession(String requestToken, String apiSecret) throws MarketException {
        try {
            return kiteConnect.generateSession(requestToken, apiSecret);
        } catch (KiteException | IOException e) {
            throw new MarketException("zerodha", extractErrorCode(e), "Failed to generate session", e);
        }
    }

    @Override
    public String getLoginURL() throws MarketException {
        try {
            return kiteConnect.getLoginURL();
        } catch (Exception e) {
            throw new MarketException("zerodha", extractErrorCode(e), "Failed to get login URL", e);
        }
    }

    @Override
    public void setAccessToken(String accessToken) {
        kiteConnect.setAccessToken(accessToken);
    }

    @Override
    public void setPublicToken(String publicToken) {
        kiteConnect.setPublicToken(publicToken);
    }

    @Override
    public void setUserId(String userId) {
        kiteConnect.setUserId(userId);
    }

    // ==================== Helper Methods ====================

    /**
     * Extract error code from exceptions
     */
    private String extractErrorCode(Throwable e) {
        if (e instanceof KiteException) {
            KiteException ke = (KiteException) e;
            // KiteException has error code and message
            return String.valueOf(ke.code);
        }
        return e.getClass().getSimpleName();
    }
}
