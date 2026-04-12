package com.dtech.kitecon.trade.repository;

import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.TradeOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {
    List<TradeOrder> findBySignal(TradeSignal signal);
    List<TradeOrder> findBySignalAndStatus(TradeSignal signal, TradeOrderStatus status);
    List<TradeOrder> findByUnderlyingSymbolAndStatus(String symbol, TradeOrderStatus status);
    List<TradeOrder> findByStatusOrderByCreatedAtDesc(TradeOrderStatus status);
    List<TradeOrder> findAllByOrderByCreatedAtDesc();
    List<TradeOrder> findByEntryTimeBetweenOrderByEntryTimeDesc(Instant from, Instant to);
}
