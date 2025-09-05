package com.dtech.kitecon.controller;

import com.dtech.algo.runner.candle.KiteTickerService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class KiteSubscribeControllerTest {

    @Mock
    private KiteTickerService kiteTickerService;
    
    @Mock
    private InstrumentRepository instrumentRepository;

    @InjectMocks
    private KiteSubscribeController kiteSubscribeController;

    private List<String> testInstrumentNames;
    private List<Instrument> testInstruments;

    @BeforeEach
    void setUp() {
        // Set up test data
        testInstrumentNames = Arrays.asList("RELIANCE", "INFY");
        
        testInstruments = new ArrayList<>();
        
        Instrument instrument1 = new Instrument();
        instrument1.setInstrumentToken(256265L);
        instrument1.setTradingsymbol("RELIANCE");
        instrument1.setExchange("NSE");
        
        Instrument instrument2 = new Instrument();
        instrument2.setInstrumentToken(408065L);
        instrument2.setTradingsymbol("INFY");
        instrument2.setExchange("NSE");
        
        testInstruments.add(instrument1);
        testInstruments.add(instrument2);
    }

    @Test
    void testSubscribe_Success() {
        // Mock the repository to return instruments when searched by name
        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExchangeIn(eq("RELIANCE"), any()))
            .thenReturn(List.of(testInstruments.get(0)));
        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExchangeIn(eq("INFY"), any()))
            .thenReturn(List.of(testInstruments.get(1)));
        
        // Mock the service method
        doNothing().when(kiteTickerService).subscribe(any());

        // Call the controller method
        ResponseEntity<Map<String, Object>> response = kiteSubscribeController.subscribe(testInstrumentNames);

        // Verify the response
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        assertTrue(response.getBody().get("message").toString().contains("Subscribed to 2 instruments"));
        
        // Verify the repository and service were called
        verify(instrumentRepository).findAllByTradingsymbolStartingWithAndExchangeIn(eq("RELIANCE"), any());
        verify(instrumentRepository).findAllByTradingsymbolStartingWithAndExchangeIn(eq("INFY"), any());
        verify(kiteTickerService).subscribe(any());
    }

    @Test
    void testSubscribe_Exception() {
        // Mock the repository to return instruments when searched by name
        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExchangeIn(anyString(), any()))
            .thenReturn(testInstruments);
            
        // Mock the service method to throw an exception
        doThrow(new RuntimeException("Test exception")).when(kiteTickerService).subscribe(any());

        // Call the controller method
        ResponseEntity<Map<String, Object>> response = kiteSubscribeController.subscribe(testInstrumentNames);

        // Verify the response
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("error", response.getBody().get("status"));
        assertTrue(response.getBody().get("message").toString().contains("Failed to subscribe"));
        
        // Verify the repository and service were called
        verify(instrumentRepository, atLeastOnce()).findAllByTradingsymbolStartingWithAndExchangeIn(anyString(), any());
        verify(kiteTickerService).subscribe(any());
    }

    @Test
    void testUnsubscribe_Success() {
        // Mock the repository to return instruments when searched by name
        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExchangeIn(eq("RELIANCE"), any()))
            .thenReturn(List.of(testInstruments.get(0)));
        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExchangeIn(eq("INFY"), any()))
            .thenReturn(List.of(testInstruments.get(1)));
            
        // Mock the service method
        doNothing().when(kiteTickerService).unsubscribe(any());

        // Call the controller method
        ResponseEntity<Map<String, Object>> response = kiteSubscribeController.unsubscribe(testInstrumentNames);

        // Verify the response
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        assertTrue(response.getBody().get("message").toString().contains("Unsubscribed from 2 instruments"));
        
        // Verify the repository and service were called
        verify(instrumentRepository).findAllByTradingsymbolStartingWithAndExchangeIn(eq("RELIANCE"), any());
        verify(instrumentRepository).findAllByTradingsymbolStartingWithAndExchangeIn(eq("INFY"), any());
        verify(kiteTickerService).unsubscribe(any());
    }

    @Test
    void testUnsubscribe_Exception() {
        // Mock the repository to return instruments when searched by name
        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExchangeIn(anyString(), any()))
            .thenReturn(testInstruments);
            
        // Mock the service method to throw an exception
        doThrow(new RuntimeException("Test exception")).when(kiteTickerService).unsubscribe(any());

        // Call the controller method
        ResponseEntity<Map<String, Object>> response = kiteSubscribeController.unsubscribe(testInstrumentNames);

        // Verify the response
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("error", response.getBody().get("status"));
        assertTrue(response.getBody().get("message").toString().contains("Failed to unsubscribe"));
        
        // Verify the repository and service were called
        verify(instrumentRepository, atLeastOnce()).findAllByTradingsymbolStartingWithAndExchangeIn(anyString(), any());
        verify(kiteTickerService).unsubscribe(any());
    }

    @Test
    void testGetSubscribedInstruments_Success() {
        // Call the controller method
        ResponseEntity<Map<String, Object>> response = kiteSubscribeController.getSubscribedInstruments();

        // Verify the response
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        assertEquals("Subscription service is active", response.getBody().get("message"));
        assertNotNull(response.getBody().get("timestamp"));
    }
}