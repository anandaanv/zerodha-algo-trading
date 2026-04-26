package com.dtech.kitecon.market.facade;

import com.dtech.dhan.config.DhanConnectConfig;
import com.dtech.dhan.facade.DhanMarketFacade;
import com.dtech.kitecon.config.KiteConnectPool;
import com.zerodhatech.kiteconnect.KiteConnect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MarketFacadeProvider which routes between Zerodha and Dhan brokers.
 * Tests facade creation, broker availability, and broker routing logic.
 */
@ExtendWith(MockitoExtension.class)
class MarketFacadeProviderTest {

    @Mock
    private KiteConnectPool kiteConnectPool;

    @Mock
    private DhanConnectConfig dhanConnectConfig;

    @Mock
    private KiteConnect kiteConnect;

    private MarketFacadeProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MarketFacadeProvider(kiteConnectPool, dhanConnectConfig);
    }

    /**
     * Verify that getFacade() without arguments returns a Zerodha facade by default.
     */
    @Test
    void testGetFacade_DefaultReturnsZerodha() {
        when(kiteConnectPool.getNextClientForHistorical()).thenReturn(kiteConnect);

        MarketFacade facade = provider.getFacade();

        assertNotNull(facade);
        assertEquals("zerodha", facade.getBrokerName());
        assertTrue(facade instanceof ZerodhaMarketFacade);
    }

    /**
     * Verify that getFacade("zerodha") explicitly returns a Zerodha facade.
     */
    @Test
    void testGetFacade_WithZerodhaNameReturnsZerodhaFacade() {
        when(kiteConnectPool.getNextClientForHistorical()).thenReturn(kiteConnect);

        MarketFacade facade = provider.getFacade("zerodha");

        assertNotNull(facade);
        assertEquals("zerodha", facade.getBrokerName());
        assertTrue(facade instanceof ZerodhaMarketFacade);
    }

    /**
     * Verify that getFacade(null) defaults to Zerodha broker.
     */
    @Test
    void testGetFacade_WithNullBrokerNameReturnsZerodha() {
        when(kiteConnectPool.getNextClientForHistorical()).thenReturn(kiteConnect);

        MarketFacade facade = provider.getFacade(null);

        assertNotNull(facade);
        assertEquals("zerodha", facade.getBrokerName());
    }

    /**
     * Verify that getFacade("dhan") returns a Dhan facade when Dhan is configured.
     */
    @Test
    void testGetFacade_WithDhanNameReturnsDhanFacade() {
        when(dhanConnectConfig.isConfigured()).thenReturn(true);
        when(dhanConnectConfig.getAccessToken()).thenReturn("dhan_token");
        when(dhanConnectConfig.getClientId()).thenReturn("dhan_client_id");

        MarketFacade facade = provider.getFacade("dhan");

        assertNotNull(facade);
        assertEquals("dhan", facade.getBrokerName());
        assertTrue(facade instanceof DhanMarketFacade);
    }

    /**
     * Verify that getFacade() throws IllegalStateException when Zerodha client pool is empty.
     */
    @Test
    void testGetFacade_WithEmptyPoolThrowsException() {
        when(kiteConnectPool.getNextClientForHistorical()).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> provider.getFacade());
    }

    /**
     * Verify that getFacade("unknown") with unsupported broker throws IllegalArgumentException.
     */
    @Test
    void testGetFacade_WithUnsupportedBrokerThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> provider.getFacade("unknown"));
    }

    /**
     * Verify that broker names are case-insensitive (ZERODHA works like zerodha).
     */
    @Test
    void testGetFacade_CaseSensitivelyHandlesBrokerNames() {
        when(kiteConnectPool.getNextClientForHistorical()).thenReturn(kiteConnect);

        MarketFacade facade = provider.getFacade("ZERODHA");

        assertNotNull(facade);
        assertEquals("zerodha", facade.getBrokerName());
    }

    /**
     * Verify that isBrokerAvailable returns true for available Zerodha.
     */
    @Test
    void testIsBrokerAvailable_WithAvailableBroker() {
        when(kiteConnectPool.getNextClientForHistorical()).thenReturn(kiteConnect);
        when(kiteConnect.getAccessToken()).thenReturn("valid_token");

        boolean available = provider.isBrokerAvailable("zerodha");

        assertTrue(available);
    }

    /**
     * Verify that isBrokerAvailable returns false when Dhan is not configured.
     */
    @Test
    void testIsBrokerAvailable_WithUnavailableBroker() {
        when(dhanConnectConfig.isConfigured()).thenReturn(false);

        boolean available = provider.isBrokerAvailable("dhan");

        assertFalse(available);
    }

    /**
     * Verify that isBrokerAvailable returns false when broker throws exception.
     */
    @Test
    void testIsBrokerAvailable_BrokerThrowsException() {
        when(kiteConnectPool.getNextClientForHistorical()).thenThrow(new RuntimeException("Connection failed"));

        boolean available = provider.isBrokerAvailable("zerodha");

        assertFalse(available);
    }

    /**
     * Verify that getAvailableBrokers always includes zerodha.
     */
    @Test
    void testGetAvailableBrokers_AlwaysIncludesZerodha() {
        when(dhanConnectConfig.isConfigured()).thenReturn(false);

        String[] brokers = provider.getAvailableBrokers();

        assertNotNull(brokers);
        assertTrue(brokers.length >= 1);
        assertTrue(java.util.Arrays.asList(brokers).contains("zerodha"));
    }

    /**
     * Verify that getAvailableBrokers includes dhan only when configured.
     */
    @Test
    void testGetAvailableBrokers_IncludesDhanWhenConfigured() {
        when(dhanConnectConfig.isConfigured()).thenReturn(true);

        String[] brokers = provider.getAvailableBrokers();

        assertNotNull(brokers);
        assertTrue(java.util.Arrays.asList(brokers).contains("zerodha"));
        assertTrue(java.util.Arrays.asList(brokers).contains("dhan"));
    }

    /**
     * Verify that getAvailableBrokers excludes dhan when not configured.
     */
    @Test
    void testGetAvailableBrokers_ExcludesDhanWhenNotConfigured() {
        when(dhanConnectConfig.isConfigured()).thenReturn(false);

        String[] brokers = provider.getAvailableBrokers();

        assertNotNull(brokers);
        assertTrue(java.util.Arrays.asList(brokers).contains("zerodha"));
        assertFalse(java.util.Arrays.asList(brokers).contains("dhan"));
    }

    /**
     * Verify that getFacadeForStrategy returns default (Zerodha) broker.
     */
    @Test
    void testGetFacadeForStrategy_ReturnsDefaultBroker() {
        when(kiteConnectPool.getNextClientForHistorical()).thenReturn(kiteConnect);

        MarketFacade facade = provider.getFacadeForStrategy("strategy_123");

        assertNotNull(facade);
        assertEquals("zerodha", facade.getBrokerName());
    }
}
