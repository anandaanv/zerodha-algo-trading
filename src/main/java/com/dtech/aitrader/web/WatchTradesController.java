package com.dtech.aitrader.web;

import com.dtech.aitrader.data.WatchTrade;
import com.dtech.aitrader.repository.WatchTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/watch-trades")
@RequiredArgsConstructor
@Slf4j
public class WatchTradesController {

    private final WatchTradeRepository watchTradeRepository;

    /**
     * GET /api/watch-trades?status=WATCHING
     * List watch trades by status.
     */
    @GetMapping
    public List<WatchTrade> getWatchTrades(
            @RequestParam(required = false) String status,
            Authentication auth) {

        log.info("GET /api/watch-trades - status={}, user={}", status, auth.getName());

        try {
            if (status != null && !status.isEmpty()) {
                return watchTradeRepository.findByStatus(status);
            }
            return watchTradeRepository.findAll();

        } catch (Exception e) {
            log.error("Error in GET /api/watch-trades", e);
            throw new RuntimeException("Failed to fetch watch trades: " + e.getMessage(), e);
        }
    }
}
