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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class ZerodhaMarketFacadeTest {

    @Mock
    private KiteConnect kiteConnect;

    private ZerodhaMarketFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ZerodhaMarketFacade(kiteConnect);
    }

    @Test
    void getBrokerNameReturnsZerodha() {
        assertEquals("zerodha", facade.getBrokerName());
    }

    @Test
    void getQuoteDelegatesToKite() throws MarketException, KiteException, IOException {
        Quote quote = new Quote();
        org.mockito.Mockito.when(kiteConnect.getQuote(any(String[].class)))
                .thenReturn(Map.of("NSE:RELIANCE", quote));

        Map<String, Quote> result = facade.getQuote(new String[]{"NSE:RELIANCE"});
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void getLTPDelegatesToKite() throws MarketException, KiteException, IOException {
        LTPQuote ltpQuote = new LTPQuote();
        org.mockito.Mockito.when(kiteConnect.getLTP(any(String[].class)))
                .thenReturn(Map.of("NSE:RELIANCE", ltpQuote));

        Map<String, LTPQuote> result = facade.getLTP(new String[]{"NSE:RELIANCE"});
        assertNotNull(result);
    }

    @Test
    void placeOrderDelegatesToKite() throws MarketException, KiteException, IOException {
        OrderResponse response = new OrderResponse();
        response.orderId = "12345";
        org.mockito.Mockito.when(kiteConnect.placeOrder(any(OrderParams.class), eq("regular")))
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

    @Test
    void exceptionWrappedAsMarketException() throws KiteException, IOException {
        org.mockito.Mockito.when(kiteConnect.getQuote(any(String[].class)))
                .thenThrow(new KiteException("API error"));

        assertThrows(MarketException.class, () ->
                facade.getQuote(new String[]{"NSE:RELIANCE"}));
    }
}
