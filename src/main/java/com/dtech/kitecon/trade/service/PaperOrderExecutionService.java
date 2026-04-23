package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.orders.OrderManager;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.dto.QuoteResult;
import com.dtech.kitecon.trade.dto.ResolvedInstrument;
import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.ExitReason;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeOrderStatus;
import com.dtech.kitecon.trade.enums.StrategyType;
import com.dtech.kitecon.trade.enums.TradingSegment;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final OrderManager orderManager;
    private final InstrumentRepository instrumentRepository;

    @Value("${impulse.live.orders:false}")
    private boolean liveOrdersEnabled;

    public TradeOrder enter(TradeSignal signal, SegmentConfig config,
                           ResolvedInstrument resolved,
                           QuoteResult instrumentQuote,
                           QuoteResult underlyingQuote) {
        // For OPT, we always BUY the option (LONG position on the option itself),
        // regardless of whether the underlying signal is bullish (→ CE) or bearish (→ PE).
        // For EQ/FUT, the order direction mirrors the signal direction.
        TradeDirection orderDirection = (config.getSegment() == TradingSegment.OPT)
                ? TradeDirection.LONG
                : signal.getDirection();

        BigDecimal entryPrice = (orderDirection == TradeDirection.LONG)
                ? instrumentQuote.getAskPrice()
                : instrumentQuote.getBidPrice();

        int qty = capitalAllocationService.computeQuantity(
                config.getCapitalPct(), entryPrice, resolved.getLotSize());

        BigDecimal underlyingEntry = underlyingQuote != null ? underlyingQuote.getLtp() : null;

        TradeOrder order = TradeOrder.builder()
                .signal(signal)
                .symbol(resolved.getTradingSymbol())
                .underlyingSymbol(signal.getSymbol())
                .segment(config.getSegment())
                .direction(orderDirection)
                .quantity(qty)
                .lotSize(resolved.getLotSize())
                .entryPrice(entryPrice)
                .underlyingEntryPrice(underlyingEntry)
                .stopLoss(signal.getStopLoss())
                .target(signal.getTarget())
                .entryTime(Instant.now())
                .status(TradeOrderStatus.OPEN)
                .instrumentType(resolved.getInstrumentType())
                .instrumentToken(resolved.getInstrumentToken())
                .strike(resolved.getStrike())
                .expiry(resolved.getExpiry())
                .paperTrade(true)
                .build();

        order = tradeOrderRepository.save(order);

        log.info("[PaperOrder] ENTER signal={} {} segment={} qty={} price={} underlyingLtp={} sl={} target={} instrument={}",
                signal.getId(), signal.getSymbol(), config.getSegment(), qty, entryPrice,
                underlyingEntry, signal.getStopLoss(), signal.getTarget(),
                resolved.getInstrumentType());

        // Place live order on Kite if enabled (impulse only — DTB stays paper)
        if (liveOrdersEnabled && signal.getStrategyType() == StrategyType.IMPULSE) {
            try {
                Instrument kiteInstrument = instrumentRepository.findByTradingsymbolAndExchangeIn(
                        resolved.getTradingSymbol(), new String[]{"NSE", "BSE", "NFO", "BFO"});
                if (kiteInstrument != null) {
                    String direction = (orderDirection == TradeDirection.LONG) ? "BUY" : "SELL";
                    String orderId = orderManager.placeMISOrder(
                            entryPrice.doubleValue(), qty, kiteInstrument, direction);
                    order.setPaperTrade(false);
                    tradeOrderRepository.save(order);
                    log.info("[LiveOrder] PLACED orderId={} {} {} qty={} price={} instrument={}",
                            orderId, resolved.getTradingSymbol(), direction, qty, entryPrice,
                            resolved.getInstrumentType());
                } else {
                    log.warn("[LiveOrder] Instrument not found for {}, falling back to paper",
                            resolved.getTradingSymbol());
                }
            } catch (Throwable e) {
                log.error("[LiveOrder] Failed to place order for {}: {}",
                        resolved.getTradingSymbol(), e.getMessage(), e);
                // Order stays as paper trade — don't crash the flow
            }
        }

        return order;
    }

    public TradeOrder exit(TradeOrder order, QuoteResult instrumentQuote,
                          BigDecimal underlyingLtp, ExitReason reason) {
        BigDecimal exitPrice = (order.getDirection() == TradeDirection.LONG)
                ? instrumentQuote.getBidPrice()
                : instrumentQuote.getAskPrice();

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
        order.setUnderlyingExitPrice(underlyingLtp);
        order.setExitTime(Instant.now());
        order.setExitReason(reason);
        order.setRealisedPnl(pnl);
        order.setStatus(TradeOrderStatus.CLOSED);

        order = tradeOrderRepository.save(order);

        log.info("[PaperOrder] EXIT order={} {} reason={} exitPrice={} underlyingLtp={} pnl={}",
                order.getId(), order.getSymbol(), reason, exitPrice, underlyingLtp, pnl);

        return order;
    }
}
