package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.trade.dto.QuoteResult;
import com.dtech.kitecon.trade.dto.ResolvedInstrument;
import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
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
                    log.warn("No quote available for underlying symbol {}", signal.getSymbol());
                    continue;
                }

                BigDecimal ltp = underlyingQuote.getLtp();
                ResolvedInstrument resolved = instrumentResolverService.resolve(
                        signal.getSymbol(), config.getSegment(), signal.getDirection(), ltp);

                QuoteResult instrumentQuote = marketQuoteService.getQuote(
                        resolved.getTradingSymbol(), resolved.getInstrumentToken());

                if (instrumentQuote == null) {
                    instrumentQuote = underlyingQuote;
                }

                paperOrderExecutionService.enter(signal, config, resolved, instrumentQuote);

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

                if (quote == null) {
                    log.warn("No quote available for symbol {}, using entry price as fallback", order.getSymbol());
                    quote = QuoteResult.builder()
                            .symbol(order.getSymbol())
                            .instrumentToken(order.getInstrumentToken())
                            .ltp(order.getEntryPrice())
                            .askPrice(order.getEntryPrice())
                            .bidPrice(order.getEntryPrice())
                            .build();
                }

                paperOrderExecutionService.exit(order, quote, reason);

            } catch (Exception e) {
                log.error("Error exiting paper order {}: {}", order.getId(), e.getMessage(), e);
            }
        }
    }
}
