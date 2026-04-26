package com.dtech.kitecon.market.facade;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for ZerodhaMarketFacade.
 * Tests exception wrapping, delegation to KiteConnect, and API method invocations.
 */
@ExtendWith(MockitoExtension.class)
class ZerodhaMarketFacadeTest {

    @Mock
    private KiteConnect kiteConnect;

    private ZerodhaMarketFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ZerodhaMarketFacade(kiteConnect);
    }

    /**
     * Verify that getBrokerName returns "zerodha".
     */
    @Test
    void testGetBrokerName() {
        assertEquals("zerodha", facade.getBrokerName());
    }

    /**
     * Verify that isAvailable returns true when access token is present.
     */
    @Test
    void testIsAvailable_WithValidTokenReturnsTrue() {
        when(kiteConnect.getAccessToken()).thenReturn("valid_token_123");

        assertTrue(facade.isAvailable());
    }

    /**
     * Verify that isAvailable returns false when access token is null.
     */
    @Test
    void testIsAvailable_WithNullTokenReturnsFalse() {
        when(kiteConnect.getAccessToken()).thenReturn(null);

        assertFalse(facade.isAvailable());
    }

    /**
     * Verify that isAvailable returns false when exception occurs.
     */
    @Test
    void testIsAvailable_WithExceptionReturnsFalse() {
        when(kiteConnect.getAccessToken()).thenThrow(new RuntimeException("Connection failed"));

        assertFalse(facade.isAvailable());
    }

    /**
     * Verify that getAccessToken delegates to KiteConnect.
     */
    @Test
    void testGetAccessToken_ReturnsTokenFromKiteConnect() {
        String expectedToken = "test_token_abc123";
        when(kiteConnect.getAccessToken()).thenReturn(expectedToken);

        String token = facade.getAccessToken();

        assertEquals(expectedToken, token);
    }

    /**
     * Verify that getProfile successfully returns Profile object from KiteConnect.
     */
    @Test
    void testGetProfile_Success() throws MarketException, KiteException, IOException {
        Profile profile = new Profile();
        when(kiteConnect.getProfile()).thenReturn(profile);

        Profile result = facade.getProfile();

        assertNotNull(result);
    }

    /**
     * Verify that KiteException from getProfile is wrapped as MarketException.
     */
    @Test
    void testGetProfile_KiteExceptionWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.getProfile()).thenThrow(new KiteException("API error"));

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.getProfile());

        assertNotNull(exception);
        assertTrue(exception.getMessage().contains("Failed to get profile"));
    }

    /**
     * Verify that IOException from getProfile is wrapped as MarketException.
     */
    @Test
    void testGetProfile_IOExceptionWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.getProfile()).thenThrow(new IOException("Network error"));

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.getProfile());

        assertNotNull(exception);
        assertTrue(exception.isNetworkError());
    }

    /**
     * Verify that getHistoricalData successfully returns HistoricalData from KiteConnect.
     */
    @Test
    void testGetHistoricalData_Success() throws MarketException, KiteException, IOException {
        HistoricalData historicalData = new HistoricalData();
        when(kiteConnect.getHistoricalData(any(Date.class), any(Date.class), anyString(), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(historicalData);

        Date from = new Date();
        Date to = new Date();
        HistoricalData result = facade.getHistoricalData(from, to, "token123", "day", false, false);

        assertNotNull(result);
    }

    /**
     * Verify that IOException from getHistoricalData is wrapped as MarketException with network error flag.
     */
    @Test
    void testGetHistoricalData_NetworkErrorWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.getHistoricalData(any(Date.class), any(Date.class), anyString(), anyString(), anyBoolean(), anyBoolean()))
                .thenThrow(new IOException("Network timeout"));

        Date from = new Date();
        Date to = new Date();
        MarketException exception = assertThrows(MarketException.class, () ->
                facade.getHistoricalData(from, to, "token123", "day", false, false));

        assertTrue(exception.isNetworkError());
    }

    /**
     * Verify that getQuote successfully returns map of quotes from KiteConnect.
     */
    @Test
    void testGetQuote_Success() throws MarketException, KiteException, IOException {
        Quote quote = new Quote();
        when(kiteConnect.getQuote(any(String[].class)))
                .thenReturn(Map.of("NSE:RELIANCE", quote));

        Map<String, Quote> result = facade.getQuote(new String[]{"NSE:RELIANCE"});

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("NSE:RELIANCE"));
    }

    /**
     * Verify that KiteException from getQuote is wrapped as MarketException.
     */
    @Test
    void testGetQuote_KiteExceptionWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.getQuote(any(String[].class)))
                .thenThrow(new KiteException("Quote API error"));

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.getQuote(new String[]{"NSE:RELIANCE"}));

        assertNotNull(exception);
        assertTrue(exception.getMessage().contains("Failed to get quotes"));
    }

    /**
     * Verify that getLTP successfully returns map of LTP quotes from KiteConnect.
     */
    @Test
    void testGetLTP_Success() throws MarketException, KiteException, IOException {
        LTPQuote ltpQuote = new LTPQuote();
        when(kiteConnect.getLTP(any(String[].class)))
                .thenReturn(Map.of("NSE:RELIANCE", ltpQuote));

        Map<String, LTPQuote> result = facade.getLTP(new String[]{"NSE:RELIANCE"});

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    /**
     * Verify that KiteException from getLTP is wrapped as MarketException.
     */
    @Test
    void testGetLTP_KiteExceptionWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.getLTP(any(String[].class)))
                .thenThrow(new KiteException("LTP error"));

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.getLTP(new String[]{"NSE:RELIANCE"}));

        assertNotNull(exception);
    }

    /**
     * Verify that getInstruments successfully returns list of instruments from KiteConnect.
     */
    @Test
    void testGetInstruments_Success() throws MarketException, KiteException, IOException {
        Instrument instrument = new Instrument();
        instrument.tradingsymbol = "RELIANCE";
        when(kiteConnect.getInstruments()).thenReturn(Collections.singletonList(instrument));

        List<Instrument> result = facade.getInstruments();

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    /**
     * Verify that IOException from getInstruments is wrapped as MarketException.
     */
    @Test
    void testGetInstruments_IOExceptionWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.getInstruments()).thenThrow(new IOException("Network error"));

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.getInstruments());

        assertNotNull(exception);
    }

    /**
     * Verify that placeOrder successfully returns OrderResponse from KiteConnect.
     */
    @Test
    void testPlaceOrder_Success() throws MarketException, KiteException, IOException {
        OrderResponse response = new OrderResponse();
        response.orderId = "12345";
        when(kiteConnect.placeOrder(any(OrderParams.class), eq("regular")))
                .thenReturn(response);

        OrderParams params = new OrderParams();
        params.exchange = "NSE";
        params.tradingsymbol = "RELIANCE";
        params.transactionType = "BUY";
        params.quantity = 1;
        params.price = 2500.0;
        params.product = "MIS";
        params.orderType = "LIMIT";

        OrderResponse result = facade.placeOrder(params, "regular");

        assertEquals("12345", result.orderId);
    }

    /**
     * Verify that KiteException from placeOrder is wrapped as MarketException.
     */
    @Test
    void testPlaceOrder_KiteExceptionWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.placeOrder(any(OrderParams.class), anyString()))
                .thenThrow(new KiteException("Order placement failed"));

        OrderParams params = new OrderParams();
        params.exchange = "NSE";
        params.tradingsymbol = "RELIANCE";
        params.transactionType = "BUY";
        params.quantity = 1;
        params.price = 2500.0;
        params.product = "MIS";
        params.orderType = "LIMIT";

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.placeOrder(params, "regular"));

        assertNotNull(exception);
    }

    /**
     * Verify that getOrderTrades successfully returns list of trades from KiteConnect.
     */
    @Test
    void testGetOrderTrades_Success() throws MarketException, KiteException, IOException {
        Trade trade = new Trade();
        trade.orderId = "12345";
        when(kiteConnect.getOrderTrades(anyString())).thenReturn(Collections.singletonList(trade));

        List<Trade> result = facade.getOrderTrades("12345");

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    /**
     * Verify that IOException from getOrderTrades is wrapped as MarketException.
     */
    @Test
    void testGetOrderTrades_IOExceptionWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.getOrderTrades(anyString())).thenThrow(new IOException("Network error"));

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.getOrderTrades("12345"));

        assertNotNull(exception);
    }

    /**
     * Verify that generateSession successfully returns User object from KiteConnect.
     */
    @Test
    void testGenerateSession_Success() throws MarketException, KiteException, IOException {
        User user = new User();
        when(kiteConnect.generateSession(anyString(), anyString())).thenReturn(user);

        User result = facade.generateSession("request_token", "api_secret");

        assertNotNull(result);
    }

    /**
     * Verify that KiteException from generateSession is wrapped as MarketException.
     */
    @Test
    void testGenerateSession_KiteExceptionWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.generateSession(anyString(), anyString()))
                .thenThrow(new KiteException("Session generation failed"));

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.generateSession("request_token", "api_secret"));

        assertNotNull(exception);
    }

    /**
     * Verify that getLoginURL successfully returns login URL from KiteConnect.
     */
    @Test
    void testGetLoginURL_Success() throws MarketException {
        String expectedUrl = "https://kite.zerodha.com/connect/login";
        when(kiteConnect.getLoginURL()).thenReturn(expectedUrl);

        String result = facade.getLoginURL();

        assertEquals(expectedUrl, result);
    }

    /**
     * Verify that exception from getLoginURL is wrapped as MarketException.
     */
    @Test
    void testGetLoginURL_ExceptionWrappedAsMarketException() {
        doThrow(new RuntimeException("Login URL generation failed")).when(kiteConnect).getLoginURL();

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.getLoginURL());

        assertNotNull(exception);
    }

    /**
     * Verify that setAccessToken delegates to KiteConnect.
     */
    @Test
    void testSetAccessToken_DelegatesToKiteConnect() {
        String token = "new_access_token";

        facade.setAccessToken(token);

        verify(kiteConnect, times(1)).setAccessToken(token);
    }

    /**
     * Verify that setPublicToken delegates to KiteConnect.
     */
    @Test
    void testSetPublicToken_DelegatesToKiteConnect() {
        String token = "public_token";

        facade.setPublicToken(token);

        verify(kiteConnect, times(1)).setPublicToken(token);
    }

    /**
     * Verify that setUserId delegates to KiteConnect.
     */
    @Test
    void testSetUserId_DelegatesToKiteConnect() {
        String userId = "user_id_123";

        facade.setUserId(userId);

        verify(kiteConnect, times(1)).setUserId(userId);
    }

    /**
     * Verify that getMargins successfully returns Margin object for equity segment.
     */
    @Test
    void testGetMargins_EquitySegment_Success() throws MarketException, KiteException, IOException {
        Margin margin = new Margin();
        when(kiteConnect.getMargins(eq("equity"))).thenReturn(margin);

        Margin result = facade.getMargins("equity");

        assertNotNull(result);
    }

    /**
     * Verify that getMargins successfully returns Margin object for commodity segment.
     */
    @Test
    void testGetMargins_CommoditySegment_Success() throws MarketException, KiteException, IOException {
        Margin margin = new Margin();
        when(kiteConnect.getMargins(eq("commodity"))).thenReturn(margin);

        Margin result = facade.getMargins("commodity");

        assertNotNull(result);
    }

    /**
     * Verify that KiteException from getMargins is wrapped as MarketException.
     */
    @Test
    void testGetMargins_KiteExceptionWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.getMargins(anyString())).thenThrow(new KiteException("Margin fetch failed"));

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.getMargins("equity"));

        assertNotNull(exception);
    }

    /**
     * Verify that IOException from getMargins is wrapped as MarketException.
     */
    @Test
    void testGetMargins_IOExceptionWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.getMargins(anyString())).thenThrow(new IOException("Network error"));

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.getMargins("equity"));

        assertTrue(exception.isNetworkError());
    }

    /**
     * Verify that generic Exception from getMargins is wrapped as MarketException.
     */
    @Test
    void testGetMargins_GenericExceptionWrappedAsMarketException() throws KiteException, IOException {
        when(kiteConnect.getMargins(anyString())).thenThrow(new RuntimeException("Unexpected error"));

        MarketException exception = assertThrows(MarketException.class, () ->
                facade.getMargins("equity"));

        assertNotNull(exception);
    }
}
