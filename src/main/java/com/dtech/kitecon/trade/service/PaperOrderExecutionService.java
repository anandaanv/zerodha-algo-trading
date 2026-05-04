package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.data.Instrument;
import java.util.List;
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

    @Value("${trade.exit.limit.slippagePct:0.5}")
    private double exitLimitSlippagePct;

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
                .paperTrade(signal.isPaperTrade())
                .build();

        order = tradeOrderRepository.save(order);

        log.info("[PaperOrder] ENTER signal={} {} segment={} qty={} price={} underlyingLtp={} sl={} target={} instrument={}",
                signal.getId(), signal.getSymbol(), config.getSegment(), qty, entryPrice,
                underlyingEntry, signal.getStopLoss(), signal.getTarget(),
                resolved.getInstrumentType());

        // Place live order on Kite if enabled (impulse only — DTB stays paper)
        if (liveOrdersEnabled && signal.getStrategyType() == StrategyType.IMPULSE) {
            try {
                List<Instrument> instruments = instrumentRepository.findAllByTradingsymbolAndExchangeIn(
                        resolved.getTradingSymbol(), new String[]{"BSE", "NSE", "NFO", "BFO"});
                // Prefer BSE over NSE (NSE may be disabled on API key)
                Instrument kiteInstrument = instruments.stream()
                        .filter(i -> "BSE".equals(i.getExchange()))
                        .findFirst()
                        .orElse(instruments.isEmpty() ? null : instruments.get(0));
                if (kiteInstrument != null) {
                    String direction = (orderDirection == TradeDirection.LONG) ? "BUY" : "SELL";
                    String orderId = orderManager.placeOrder(
                            entryPrice.doubleValue(), qty, kiteInstrument, direction,
                            config.getOrderProduct());
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

        // Place live exit order on broker if this was a live trade
        if (liveOrdersEnabled && !order.isPaperTrade()) {
            try {
                List<Instrument> instruments = instrumentRepository.findAllByTradingsymbolAndExchangeIn(
                        order.getSymbol(), new String[]{"BSE", "NSE", "NFO", "BFO"});
                Instrument kiteInstrument = instruments.stream()
                        .filter(i -> "BSE".equals(i.getExchange()))
                        .findFirst()
                        .orElse(instruments.isEmpty() ? null : instruments.get(0));
                if (kiteInstrument != null) {
                    // Exit = reverse of entry direction
                    String exitDirection = (order.getDirection() == TradeDirection.LONG) ? "SELL" : "BUY";

                    // Compute LIMIT price for exit. Use exitPrice (bid/ask from quote) +/- slippage so
                    // the order fills quickly even if price moved between quote and submission.
                    // Slippage direction: SELL → price below LTP (give up some), BUY → price above LTP.
                    double limitPrice;
                    if (exitDirection.equals("SELL")) {
                        limitPrice = exitPrice.doubleValue() * (1.0 - exitLimitSlippagePct / 100.0);
                    } else {
                        limitPrice = exitPrice.doubleValue() * (1.0 + exitLimitSlippagePct / 100.0);
                    }
                    // Round to 2 decimals (paise) and ensure minimum tick size (0.05)
                    limitPrice = Math.max(0.05, Math.round(limitPrice * 20.0) / 20.0);

                    String orderId = orderManager.placeOrder(
                            limitPrice,
                            order.getQuantity(), kiteInstrument, exitDirection,
                            "MIS");
                    log.info("[LiveOrder] EXIT PLACED orderId={} {} {} qty={} limitPrice={} (slippage={}%) instrument={}",
                            orderId, order.getSymbol(), exitDirection, order.getQuantity(), limitPrice,
                            exitLimitSlippagePct, kiteInstrument.getTradingsymbol());
                } else {
                    log.error("[LiveOrder] EXIT FAILED — instrument not found for {}", order.getSymbol());
                }
            } catch (Throwable e) {
                log.error("[LiveOrder] EXIT FAILED for {} orderId={}: {}",
                        order.getSymbol(), order.getId(), e.getMessage(), e);
            }
        }

        return order;
    }
}
