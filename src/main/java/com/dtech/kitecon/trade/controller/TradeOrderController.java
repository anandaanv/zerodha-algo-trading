package com.dtech.kitecon.trade.controller;

import com.dtech.kitecon.trade.dto.TradeSummaryDto;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.enums.TradeOrderStatus;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@RestController
@RequestMapping("/api/trade-orders")
@RequiredArgsConstructor
@Slf4j
public class TradeOrderController {

    private final TradeOrderRepository tradeOrderRepository;

    @GetMapping("/")
    public ResponseEntity<List<TradeOrder>> getOrders(@RequestParam(required = false) String status) {
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

        return ResponseEntity.ok(orders);
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
