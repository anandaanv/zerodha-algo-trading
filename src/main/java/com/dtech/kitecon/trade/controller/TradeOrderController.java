package com.dtech.kitecon.trade.controller;

import com.dtech.kitecon.market.facade.MarketFacadeProvider;
import com.dtech.kitecon.trade.dto.TradeSummaryDto;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeOrderStatus;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import com.zerodhatech.models.LTPQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trade-orders")
@RequiredArgsConstructor
@Slf4j
public class TradeOrderController {

    private final TradeOrderRepository tradeOrderRepository;
    private final MarketFacadeProvider marketFacadeProvider;

    @GetMapping("/")
    public ResponseEntity<List<TradeOrder>> getOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String strategyType,
            @RequestParam(required = false) String pattern) {
        List<TradeOrder> orders;

        if (status != null && !status.isEmpty()) {
            try {
                TradeOrderStatus orderStatus = TradeOrderStatus.valueOf(status.toUpperCase());
                orders = tradeOrderRepository.findByStatusOrderByCreatedAtDesc(orderStatus);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        } else {
            orders = tradeOrderRepository.findAllByOrderByCreatedAtDesc();
        }

        // In-memory filter on linked signal — straightforward since order count is bounded.
        if (strategyType != null && !strategyType.isBlank()) {
            String wanted = strategyType.trim();
            orders = orders.stream()
                    .filter(o -> o.getSignal() != null
                            && o.getSignal().getStrategyType() != null
                            && wanted.equalsIgnoreCase(o.getSignal().getStrategyType().name()))
                    .toList();
        }
        if (pattern != null && !pattern.isBlank()) {
            String wanted = pattern.trim();
            orders = orders.stream()
                    .filter(o -> o.getSignal() != null
                            && wanted.equalsIgnoreCase(o.getSignal().getPatternType()))
                    .toList();
        }

        enrichWithLivePnl(orders);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/strategy-types")
    public ResponseEntity<List<String>> getDistinctStrategyTypes() {
        return ResponseEntity.ok(tradeOrderRepository.findDistinctStrategyTypes());
    }

    @GetMapping("/patterns")
    public ResponseEntity<List<String>> getDistinctPatterns() {
        return ResponseEntity.ok(tradeOrderRepository.findDistinctPatterns());
    }

    private void enrichWithLivePnl(List<TradeOrder> orders) {
        List<TradeOrder> openOrders = orders.stream()
                .filter(o -> o.getStatus() == TradeOrderStatus.OPEN)
                .toList();

        if (openOrders.isEmpty()) return;

        // Build instrument keys for batch LTP call: "NFO:SYMBOL" or "NSE:SYMBOL"
        String[] instruments = openOrders.stream()
                .map(o -> {
                    String exchange = o.getSegment().name().equals("EQ") ? "NSE" : "NFO";
                    return exchange + ":" + o.getSymbol();
                })
                .distinct()
                .toArray(String[]::new);

        try {
            Map<String, LTPQuote> ltpMap = marketFacadeProvider.getFacade().getLTP(instruments);

            for (TradeOrder order : openOrders) {
                String exchange = order.getSegment().name().equals("EQ") ? "NSE" : "NFO";
                String key = exchange + ":" + order.getSymbol();
                LTPQuote quote = ltpMap.get(key);
                if (quote == null) continue;

                BigDecimal ltp = BigDecimal.valueOf(quote.lastPrice);
                order.setLtp(ltp);

                if (order.getEntryPrice() != null && order.getQuantity() != null) {
                    BigDecimal pnl;
                    if (order.getDirection() == TradeDirection.LONG) {
                        pnl = ltp.subtract(order.getEntryPrice())
                                .multiply(BigDecimal.valueOf(order.getQuantity()));
                    } else {
                        pnl = order.getEntryPrice().subtract(ltp)
                                .multiply(BigDecimal.valueOf(order.getQuantity()));
                    }
                    order.setUnrealisedPnl(pnl.setScale(2, RoundingMode.HALF_UP));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch live LTP for open orders: {}", e.getMessage());
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<TradeSummaryDto> getSummary() {
        List<TradeOrder> closedOrders = tradeOrderRepository.findByStatusOrderByCreatedAtDesc(TradeOrderStatus.CLOSED);
        List<TradeOrder> openOrders = tradeOrderRepository.findByStatusOrderByCreatedAtDesc(TradeOrderStatus.OPEN);

        int totalTrades = closedOrders.size();
        int openTrades = openOrders.size();
        int winCount = 0;
        int lossCount = 0;
        BigDecimal totalPnl = BigDecimal.ZERO;
        BigDecimal totalWins = BigDecimal.ZERO;
        BigDecimal totalLosses = BigDecimal.ZERO;

        for (TradeOrder order : closedOrders) {
            if (order.getRealisedPnl() != null) {
                totalPnl = totalPnl.add(order.getRealisedPnl());

                if (order.getRealisedPnl().compareTo(BigDecimal.ZERO) > 0) {
                    winCount++;
                    totalWins = totalWins.add(order.getRealisedPnl());
                } else {
                    lossCount++;
                    totalLosses = totalLosses.add(order.getRealisedPnl().abs());
                }
            }
        }

        BigDecimal avgWin = winCount > 0
                ? totalWins.divide(BigDecimal.valueOf(winCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal avgLoss = lossCount > 0
                ? totalLosses.divide(BigDecimal.valueOf(lossCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal winRate = totalTrades > 0
                ? BigDecimal.valueOf(winCount).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalTrades), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        TradeSummaryDto summary = TradeSummaryDto.builder()
                .totalTrades(totalTrades)
                .openTrades(openTrades)
                .winCount(winCount)
                .lossCount(lossCount)
                .totalPnlInr(totalPnl)
                .avgWinInr(avgWin)
                .avgLossInr(avgLoss)
                .winRatePct(winRate)
                .build();

        return ResponseEntity.ok(summary);
    }
}
