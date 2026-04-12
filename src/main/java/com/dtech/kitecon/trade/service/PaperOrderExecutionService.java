package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.trade.dto.QuoteResult;
import com.dtech.kitecon.trade.dto.ResolvedInstrument;
import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.ExitReason;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeOrderStatus;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaperOrderExecutionService {

    private final TradeOrderRepository tradeOrderRepository;
    private final CapitalAllocationService capitalAllocationService;

    public TradeOrder enter(TradeSignal signal, SegmentConfig config,
                           ResolvedInstrument resolved, QuoteResult quote) {
        BigDecimal entryPrice;
        if (signal.getDirection() == TradeDirection.LONG) {
            entryPrice = quote.getAskPrice();
        } else {
            entryPrice = quote.getBidPrice();
        }

        int qty = capitalAllocationService.computeQuantity(
                config.getCapitalPct(), entryPrice, resolved.getLotSize());

        TradeOrder order = TradeOrder.builder()
                .signal(signal)
                .symbol(resolved.getTradingSymbol())
                .underlyingSymbol(signal.getSymbol())
                .segment(config.getSegment())
                .direction(signal.getDirection())
                .quantity(qty)
                .lotSize(resolved.getLotSize())
                .entryPrice(entryPrice)
                .entryTime(Instant.now())
                .status(TradeOrderStatus.OPEN)
                .instrumentType(resolved.getInstrumentType())
                .instrumentToken(resolved.getInstrumentToken())
                .strike(resolved.getStrike())
                .expiry(resolved.getExpiry())
                .paperTrade(true)
                .build();

        order = tradeOrderRepository.save(order);

        log.info("[PaperOrder] ENTER signal={} {} segment={} qty={} price={} instrument={}",
                signal.getId(), signal.getSymbol(), config.getSegment(), qty, entryPrice,
                resolved.getInstrumentType());

        return order;
    }

    public TradeOrder exit(TradeOrder order, QuoteResult quote, ExitReason reason) {
        BigDecimal exitPrice;
        if (order.getDirection() == TradeDirection.LONG) {
            exitPrice = quote.getBidPrice();
        } else {
            exitPrice = quote.getAskPrice();
        }

        BigDecimal pnl;
        if (order.getDirection() == TradeDirection.LONG) {
            pnl = exitPrice.subtract(order.getEntryPrice())
                    .multiply(BigDecimal.valueOf(order.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);
        } else {
            pnl = order.getEntryPrice().subtract(exitPrice)
                    .multiply(BigDecimal.valueOf(order.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        order.setExitPrice(exitPrice);
        order.setExitTime(Instant.now());
        order.setExitReason(reason);
        order.setRealisedPnl(pnl);
        order.setStatus(TradeOrderStatus.CLOSED);

        order = tradeOrderRepository.save(order);

        log.info("[PaperOrder] EXIT order={} {} reason={} exitPrice={} pnl={}",
                order.getId(), order.getSymbol(), reason, exitPrice, pnl);

        return order;
    }
}
