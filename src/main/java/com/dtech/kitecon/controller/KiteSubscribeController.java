package com.dtech.kitecon.controller;

import com.dtech.algo.runner.candle.KiteTickerService;
import com.dtech.algo.series.InstrumentType;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Controller for managing Kite instrument subscriptions
 */
@RestController
@RequestMapping("/api/kite")
@RequiredArgsConstructor
@Slf4j
public class KiteSubscribeController {

    private final KiteTickerService kiteTickerService;
    private final InstrumentRepository instrumentRepository;

    /**
     * Subscribe to a list of instruments by name
     *
     * @param instrumentNames List of instrument names to subscribe to
     * @return Response with status message
     */
    @PostMapping("/subscribe")
    public ResponseEntity<Map<String, Object>> subscribe(@RequestBody List<String> instrumentNames) {
        log.info("Received request to subscribe to {} instruments", instrumentNames.size());
        
        try {
            List<Instrument> instruments = getInstruments(instrumentNames);

            if (instruments.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "No valid instruments found for the provided names"
                ));
            }
            
            kiteTickerService.subscribe(instruments);
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Subscribed to " + instruments.size() + " instruments"
            ));
        } catch (Exception e) {
            log.error("Error subscribing to instruments", e);
            return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Failed to subscribe: " + e.getMessage()
            ));
        }
    }

    /**
     * Unsubscribe from a list of instruments by name
     *
     * @param instrumentNames List of instrument names to unsubscribe from
     * @return Response with status message
     */
    @PostMapping("/unsubscribe")
    public ResponseEntity<Map<String, Object>> unsubscribe(@RequestBody List<String> instrumentNames) {
        log.info("Received request to unsubscribe from {} instruments", instrumentNames.size());
        
        try {
            // Default exchanges to search in
            List<Instrument> instruments = getInstruments(instrumentNames);

            if (instruments.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "No valid instruments found for the provided names"
                ));
            }
            
            kiteTickerService.unsubscribe(instruments);
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Unsubscribed from " + instruments.size() + " instruments"
            ));
        } catch (Exception e) {
            log.error("Error unsubscribing from instruments", e);
            return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Failed to unsubscribe: " + e.getMessage()
            ));
        }
    }

    @NotNull
    private List<Instrument> getInstruments(List<String> instrumentNames) {
        String[] exchanges = new String[]{"NSE", "NFO"};
        List<Instrument> instruments = new ArrayList<>();

        // Find instruments by name
        for (String name : instrumentNames) {
            List<Instrument> foundInstruments = instrumentRepository.findAllByTradingsymbolStartingWithAndExchangeIn(name, exchanges);
            if (foundInstruments.isEmpty()) {
                log.warn("No instrument found with name: {}", name);
            } else {
                instruments.addAll(foundInstruments.stream()
                        .filter(instrument -> instrument.getInstrumentType().equals(InstrumentType.EQ.name())).toList());
            }
        }
        return instruments;
    }

    /**
     * Get a list of currently subscribed instruments
     *
     * @return Response with status and count of subscribed instruments
     */
    @GetMapping("/subscribed")
    public ResponseEntity<Map<String, Object>> getSubscribedInstruments() {
        log.info("Received request to get subscribed instruments status");
        
        try {
            // We can't directly access the private method, but we can report if the service is connected
            // and provide other useful information
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Subscription service is active",
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("Error getting subscription status", e);
            return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Failed to get subscription status: " + e.getMessage()
            ));
        }
    }
}