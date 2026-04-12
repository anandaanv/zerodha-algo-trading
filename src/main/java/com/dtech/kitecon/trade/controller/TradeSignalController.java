package com.dtech.kitecon.trade.controller;

import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trade-signals")
@RequiredArgsConstructor
@Slf4j
public class TradeSignalController {

    private final TradeSignalRepository tradeSignalRepository;
    private final TradeOrderRepository tradeOrderRepository;

    @GetMapping({"", "/"})
    public ResponseEntity<List<TradeSignal>> getSignals(@RequestParam(required = false) String status) {
        List<TradeSignal> signals;

        if (status != null && !status.isEmpty()) {
            try {
                TradeStatus tradeStatus = TradeStatus.valueOf(status.toUpperCase());
                signals = tradeSignalRepository.findByStatus(tradeStatus);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid TradeStatus: {}", status);
                return ResponseEntity.badRequest().build();
            }
        } else {
            signals = tradeSignalRepository.findAll();
        }

        return ResponseEntity.ok(signals);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TradeSignal> getSignalById(@PathVariable Long id) {
        return tradeSignalRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-ids")
    public ResponseEntity<List<TradeSignal>> getSignalsByIds(@RequestParam String ids) {
        try {
            List<Long> idList = List.of(ids.split(","))
                    .stream()
                    .map(String::trim)
                    .map(Long::parseLong)
                    .toList();
            List<TradeSignal> signals = tradeSignalRepository.findAllById(idList);
            return ResponseEntity.ok(signals);
        } catch (NumberFormatException e) {
            log.warn("Invalid ID format in comma-separated list: {}", ids);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{id}/orders")
    public ResponseEntity<List<TradeOrder>> getOrdersForSignal(@PathVariable Long id) {
        // Verify signal exists
        if (!tradeSignalRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<TradeOrder> orders = tradeOrderRepository.findBySignal_Id(id);
        return ResponseEntity.ok(orders);
    }
}
