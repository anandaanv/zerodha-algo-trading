package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.CopilotActiveTrade;
import com.dtech.kitecon.data.copilot.CopilotHypothesis;
import com.dtech.kitecon.repository.copilot.CopilotActiveTradeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Manages the active trade lifecycle.
 *
 * Key rules (from spec):
 * - A hypothesis is NOT a trade. A trade is a confirmed, entered hypothesis.
 * - A trade is always linked to its parent hypothesis.
 * - Override trades are first-class entities — tracked separately with full audit trail.
 * - Agreement gate: expert must explicitly enter (POST /trades/:id/open) — no auto-entry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotTradeService {

    private final CopilotActiveTradeRepository tradeRepository;
    private final CopilotHypothesisService hypothesisService;
    private final ObjectMapper objectMapper;

    /**
     * Create a trade from a confirmed hypothesis.
     * Called when expert approves an ENTRY_SIGNAL.
     *
     * @param hypothesis   the confirmed hypothesis
     * @param entryType    ANTICIPATORY | CONFIRMATION
     * @param isOverride   true if expert is entering against system objection
     * @param systemObjection the system's objection text (if override)
     * @param overrideReason  expert's reasoning for the override
     */
    @Transactional
    public CopilotActiveTrade createTrade(CopilotHypothesis hypothesis, String entryType,
                                           boolean isOverride, String systemObjection,
                                           String overrideReason) {
        // Pre-calculate both entry types from hypothesis data
        TradeParameters params = extractTradeParameters(hypothesis, entryType);

        CopilotActiveTrade trade = CopilotActiveTrade.builder()
                .hypothesisId(hypothesis.getId())
                .investigationId(hypothesis.getInvestigationId())
                .symbol(hypothesis.getSymbol())
                .entryType(entryType)
                .sl(params.sl())
                .tp(params.tp())
                .state("ENTERED")
                .origin(hypothesis.getOrigin())
                .isOverrideTrade(isOverride)
                .systemObjection(systemObjection)
                .overrideReason(overrideReason)
                .openedAt(Instant.now())
                .build();

        trade = tradeRepository.save(trade);

        // Update hypothesis state to TRADE_ACTIVE (hypothesis stays on board)
        hypothesisService.transitionState(hypothesis.getId(), "TRADE_ACTIVE", null);

        log.info("Trade {} created for hypothesis {} ({}{})",
                trade.getId(), hypothesis.getId(), hypothesis.getLabel(),
                isOverride ? " — OVERRIDE" : "");

        return trade;
    }

    /**
     * Expert enters the trade with actual price and size.
     */
    @Transactional
    public CopilotActiveTrade enterTrade(Long tradeId, BigDecimal entryPrice, BigDecimal size) {
        CopilotActiveTrade trade = getOrThrow(tradeId);
        trade.setEntryPrice(entryPrice);
        trade.setSize(size);
        trade.setState("MONITORING");
        trade.setOpenedAt(Instant.now());
        return tradeRepository.save(trade);
    }

    /**
     * Close a trade with outcome.
     */
    @Transactional
    public CopilotActiveTrade closeTrade(Long tradeId, BigDecimal closePrice,
                                          String outcome, String notes) {
        CopilotActiveTrade trade = getOrThrow(tradeId);
        trade.setClosePrice(closePrice);
        trade.setOutcome(outcome);
        trade.setCloseNotes(notes);
        trade.setState("CLOSED");
        trade.setClosedAt(Instant.now());

        // For override trades, record whether system concern materialized
        if (trade.getIsOverrideTrade()) {
            boolean systemWasRight = "LOSS".equals(outcome);
            trade.setSystemConcernMaterialized(systemWasRight);
        }

        return tradeRepository.save(trade);
    }

    /**
     * Log an override — expert is entering against system objection.
     * Creates an override record linked to the trade.
     */
    @Transactional
    public CopilotActiveTrade logOverride(Long tradeId, String systemObjection, String expertReason) {
        CopilotActiveTrade trade = getOrThrow(tradeId);
        trade.setIsOverrideTrade(true);
        trade.setSystemObjection(systemObjection);
        trade.setOverrideReason(expertReason);
        return tradeRepository.save(trade);
    }

    public List<CopilotActiveTrade> getActiveTrades(Long investigationId) {
        return tradeRepository.findByInvestigationIdAndStateIn(investigationId,
                List.of("ENTERED", "MONITORING"));
    }

    public List<CopilotActiveTrade> getOverrideTrades() {
        return tradeRepository.findByIsOverrideTradeTrue();
    }

    public Optional<CopilotActiveTrade> getTradeForHypothesis(Long hypothesisId) {
        List<CopilotActiveTrade> trades = tradeRepository.findByHypothesisId(hypothesisId);
        return trades.stream()
                .filter(t -> !t.getState().equals("CLOSED"))
                .findFirst();
    }

    public CopilotActiveTrade getOrThrow(Long id) {
        return tradeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Trade not found: " + id));
    }

    // ─── Dual entry parameter calculation ────────────────────────────────────

    private TradeParameters extractTradeParameters(CopilotHypothesis hypothesis, String entryType) {
        try {
            String json = "ANTICIPATORY".equals(entryType)
                    ? hypothesis.getAnticipatoryTrade()
                    : hypothesis.getConfirmationTrade();

            if (json == null || json.isBlank()) return new TradeParameters(null, null);

            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            BigDecimal sl = parseBigDecimal(node.path("sl").asText(null));
            BigDecimal tp = parseBigDecimal(node.path("tp").asText(null));
            return new TradeParameters(sl, tp);
        } catch (Exception e) {
            log.warn("Could not extract trade parameters from hypothesis {}: {}", hypothesis.getId(), e.getMessage());
            return new TradeParameters(null, null);
        }
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return null;
        try {
            // Handle ranges like "42500-43000" — take midpoint
            if (value.contains("-")) {
                String[] parts = value.split("-");
                BigDecimal low = new BigDecimal(parts[0].trim().replaceAll("[^0-9.]", ""));
                BigDecimal high = new BigDecimal(parts[1].trim().replaceAll("[^0-9.]", ""));
                return low.add(high).divide(BigDecimal.TWO);
            }
            return new BigDecimal(value.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private record TradeParameters(BigDecimal sl, BigDecimal tp) {}
}
