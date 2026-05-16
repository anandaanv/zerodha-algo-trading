package com.dtech.aitrader.web;

import com.dtech.aitrader.data.PaperTrade;
import com.dtech.aitrader.repository.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai-trader")
@RequiredArgsConstructor
@Slf4j
public class AiTraderPaperTradesController {

    private final PaperTradeRepository paperTradeRepository;

    /**
     * GET /api/ai-trader/paper-trades?status=OPEN
     * List paper trades by status.
     */
    @GetMapping("/paper-trades")
    public List<PaperTrade> getPaperTrades(
            @RequestParam(required = false) String status,
            Authentication auth) {

        log.info("GET /api/ai-trader/paper-trades - status={}, user={}", status, auth.getName());

        try {
            if (status != null && !status.isEmpty()) {
                return paperTradeRepository.findByStatus(status);
            }
            return paperTradeRepository.findAll();

        } catch (Exception e) {
            log.error("Error in GET /api/ai-trader/paper-trades", e);
            throw new RuntimeException("Failed to fetch paper trades: " + e.getMessage(), e);
        }
    }
}
