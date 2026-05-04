package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.trade.dto.QuoteResult;
import com.dtech.kitecon.trade.dto.ResolvedInstrument;
import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.entity.TradeActionLog;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.StrategyType;
import com.dtech.kitecon.trade.enums.ExitReason;
import com.dtech.kitecon.trade.enums.TradeOrderStatus;
import com.dtech.kitecon.trade.repository.SegmentConfigRepository;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradeOrchestrationService {

    private final SegmentConfigRepository segmentConfigRepository;
    private final InstrumentResolverService instrumentResolverService;
    private final MarketQuoteService marketQuoteService;
    private final PaperOrderExecutionService paperOrderExecutionService;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeActionLogger tradeActionLogger;
    private final BrokerOrderService brokerOrderService;

    public void onEntryTriggered(TradeSignal signal) {
        List<SegmentConfig> configs = segmentConfigRepository.findBySymbolAndEnabledTrue(signal.getSymbol());

        if (configs.isEmpty()) {
            log.info("No enabled segment configs for {}, skipping order creation", signal.getSymbol());
            return;
        }

        for (SegmentConfig config : configs) {
            try {
                QuoteResult underlyingQuote = marketQuoteService.getQuote(
                        signal.getSymbol(), signal.getInstrumentToken());

                if (underlyingQuote == null) {
                    log.warn("No underlying quote for {}, skipping entry for segment {}",
                            signal.getSymbol(), config.getSegment());
                    continue;
                }

                BigDecimal ltp = underlyingQuote.getLtp();
                // Use OTM for impulse strategy, ATM for DTB
                double otmPct = (signal.getStrategyType() == StrategyType.IMPULSE) ? 0.03 : 0.0;
                ResolvedInstrument resolved = instrumentResolverService.resolve(
                        signal.getSymbol(), config.getSegment(), signal.getDirection(), ltp, otmPct);

                QuoteResult instrumentQuote = marketQuoteService.getQuote(
                        resolved.getTradingSymbol(), resolved.getInstrumentToken());

                if (instrumentQuote == null) {
                    log.warn("No instrument quote for {} (token={}), skipping entry. " +
                            "Will retry on next entry-handler poll.",
                            resolved.getTradingSymbol(), resolved.getInstrumentToken());
                    continue;
                }

                TradeOrder order = paperOrderExecutionService.enter(signal, config, resolved, instrumentQuote, underlyingQuote);
                if (order != null) {
                    try {
                        tradeActionLogger.log(signal, TradeActionLog.TradeAction.ENTRY_FILLED, ltp, "Order placed");
                    } catch (Exception e) {
                        log.warn("Failed to log trade action: {}", e.getMessage());
                    }
                }

            } catch (Exception e) {
                log.error("Error creating paper order for signal {} segment {}: {}",
                        signal.getId(), config.getSegment(), e.getMessage(), e);
            }
        }
    }

    public void onExitTriggered(TradeSignal signal, ExitReason reason) {
        List<TradeOrder> openOrders = tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.OPEN);

        for (TradeOrder order : openOrders) {
            try {
                QuoteResult quote = marketQuoteService.getQuote(order.getSymbol(), order.getInstrumentToken());

                // If quote fetch failed, fall back to broker LTP. If still null, last-ditch use entry price.
                // Don't silent-skip — the goal is to ALWAYS place an exit (limit) order on the broker.
                if (quote == null) {
                    BigDecimal fallbackLtp = brokerOrderService.fetchLtp(order.getSymbol(), order.getInstrumentToken());
                    if (fallbackLtp == null) {
                        fallbackLtp = order.getEntryPrice();
                        log.warn("No quote AND no LTP for order {} {} — using entry price {} as fallback for exit limit",
                                order.getId(), order.getSymbol(), fallbackLtp);
                    }
                    // Build a synthetic QuoteResult with bid=ask=ltp so PaperOrderExecutionService.exit can compute exit price
                    quote = QuoteResult.builder()
                            .ltp(fallbackLtp)
                            .bidPrice(fallbackLtp)
                            .askPrice(fallbackLtp)
                            .build();
                }

                // Best-effort capture of underlying spot for audit.
                BigDecimal underlyingLtp = null;
                try {
                    QuoteResult underlyingQuote = marketQuoteService.getQuote(
                            order.getUnderlyingSymbol(), null);
                    if (underlyingQuote != null) {
                        underlyingLtp = underlyingQuote.getLtp();
                    }
                } catch (Exception ue) {
                    log.debug("Underlying quote fetch failed for {}: {}",
                            order.getUnderlyingSymbol(), ue.getMessage());
                }

                paperOrderExecutionService.exit(order, quote, underlyingLtp, reason);
                try {
                    TradeActionLog.TradeAction exitAction = reason == ExitReason.STOP_HIT
                            ? TradeActionLog.TradeAction.STOP_HIT
                            : (reason == ExitReason.TARGET_HIT
                                ? TradeActionLog.TradeAction.TARGET_HIT
                                : TradeActionLog.TradeAction.REVERSAL_EXIT);
                    tradeActionLogger.logWithPnl(signal, exitAction, quote.getLtp(), null,
                            "Exit reason=" + reason);
                } catch (Exception e) {
                    log.warn("Failed to log trade action: {}", e.getMessage());
                }

            } catch (Exception e) {
                log.error("Error exiting paper order {}: {}", order.getId(), e.getMessage(), e);
            }
        }
    }
}
