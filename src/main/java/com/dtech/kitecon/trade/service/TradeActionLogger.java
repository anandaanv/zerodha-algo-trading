package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.trade.entity.TradeActionLog;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.repository.TradeActionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradeActionLogger {

    private final TradeActionLogRepository repository;

    public void log(TradeSignal signal, TradeActionLog.TradeAction action, BigDecimal price, String detail) {
        TradeActionLog entry = TradeActionLog.builder()
            .signal(signal)
            .symbol(signal.getSymbol())
            .action(action)
            .price(price)
            .stopLevel(signal.getStopLoss())
            .targetLevel(signal.getTarget())
            .slabIndex(signal.getCurrentSlabIndex())
            .timestamp(Instant.now())
            .detail(detail)
            .build();
        repository.save(entry);
        log.info("[TradeLog] {} {} {} @ {} \u2014 {}", signal.getSymbol(), signal.getDirection(), action, price, detail);
    }

    public void logWithPnl(TradeSignal signal, TradeActionLog.TradeAction action, BigDecimal price, BigDecimal pnlPct, String detail) {
        TradeActionLog entry = TradeActionLog.builder()
            .signal(signal)
            .symbol(signal.getSymbol())
            .action(action)
            .price(price)
            .stopLevel(signal.getStopLoss())
            .targetLevel(signal.getTarget())
            .slabIndex(signal.getCurrentSlabIndex())
            .pnlPct(pnlPct)
            .timestamp(Instant.now())
            .detail(detail)
            .build();
        repository.save(entry);
        log.info("[TradeLog] {} {} {} @ {} P&L={}% \u2014 {}", signal.getSymbol(), signal.getDirection(), action, price, pnlPct, detail);
    }

    public void logSlab(TradeSignal signal, int slabIndex, BigDecimal price, BigDecimal newStop) {
        TradeActionLog entry = TradeActionLog.builder()
            .signal(signal)
            .symbol(signal.getSymbol())
            .action(TradeActionLog.TradeAction.SLAB_HIT)
            .price(price)
            .stopLevel(newStop)
            .targetLevel(signal.getTarget())
            .slabIndex(slabIndex)
            .timestamp(Instant.now())
            .detail(String.format("Slab %d hit, stop\u2192%.2f", slabIndex, newStop))
            .build();
        repository.save(entry);
        log.info("[TradeLog] {} SLAB_{} hit @ {} stop\u2192{}", signal.getSymbol(), slabIndex, price, newStop);
    }

    public void log(TradeSignal signal, TradeActionLog.TradeAction action, BigDecimal price, String detail, Instant candleTime) {
        TradeActionLog entry = TradeActionLog.builder()
            .signal(signal)
            .symbol(signal.getSymbol())
            .action(action)
            .price(price)
            .stopLevel(signal.getStopLoss())
            .targetLevel(signal.getTarget())
            .slabIndex(signal.getCurrentSlabIndex())
            .timestamp(candleTime)
            .candleTime(candleTime)
            .detail(detail)
            .build();
        repository.save(entry);
        log.info("[TradeLog] {} {} {} @ {} candle={} — {}", signal.getSymbol(), signal.getDirection(), action, price, candleTime, detail);
    }

    public void logWithPnl(TradeSignal signal, TradeActionLog.TradeAction action, BigDecimal price, BigDecimal pnlPct, String detail, Instant candleTime) {
        TradeActionLog entry = TradeActionLog.builder()
            .signal(signal)
            .symbol(signal.getSymbol())
            .action(action)
            .price(price)
            .stopLevel(signal.getStopLoss())
            .targetLevel(signal.getTarget())
            .slabIndex(signal.getCurrentSlabIndex())
            .pnlPct(pnlPct)
            .timestamp(candleTime)
            .candleTime(candleTime)
            .detail(detail)
            .build();
        repository.save(entry);
        log.info("[TradeLog] {} {} {} @ {} P&L={}% candle={} — {}", signal.getSymbol(), signal.getDirection(), action, price, pnlPct, candleTime, detail);
    }

    public void logSlab(TradeSignal signal, int slabIndex, BigDecimal price, BigDecimal newStop, Instant candleTime) {
        TradeActionLog entry = TradeActionLog.builder()
            .signal(signal)
            .symbol(signal.getSymbol())
            .action(TradeActionLog.TradeAction.SLAB_HIT)
            .price(price)
            .stopLevel(newStop)
            .targetLevel(signal.getTarget())
            .slabIndex(slabIndex)
            .timestamp(candleTime)
            .candleTime(candleTime)
            .detail(String.format("Slab %d hit, stop→%.2f", slabIndex, newStop))
            .build();
        repository.save(entry);
        log.info("[TradeLog] {} SLAB_{} hit @ {} stop→{} candle={}", signal.getSymbol(), slabIndex, price, newStop, candleTime);
    }

    public List<TradeActionLog> getHistory(Long signalId) {
        return repository.findBySignalIdOrderByTimestampAsc(signalId);
    }
}
