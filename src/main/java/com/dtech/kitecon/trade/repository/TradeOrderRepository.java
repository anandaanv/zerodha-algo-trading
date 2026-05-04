package com.dtech.kitecon.trade.repository;

import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.TradeOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {
    List<TradeOrder> findBySignal(TradeSignal signal);
    List<TradeOrder> findBySignal_Id(Long signalId);
    List<TradeOrder> findBySignalAndStatus(TradeSignal signal, TradeOrderStatus status);
    List<TradeOrder> findByUnderlyingSymbolAndStatus(String symbol, TradeOrderStatus status);
    List<TradeOrder> findByStatusOrderByCreatedAtDesc(TradeOrderStatus status);
    List<TradeOrder> findAllByOrderByCreatedAtDesc();
    List<TradeOrder> findByEntryTimeBetweenOrderByEntryTimeDesc(Instant from, Instant to);

    @Query("SELECT DISTINCT CAST(o.signal.strategyType AS string) FROM TradeOrder o " +
           "WHERE o.signal.strategyType IS NOT NULL ORDER BY CAST(o.signal.strategyType AS string)")
    List<String> findDistinctStrategyTypes();

    @Query("SELECT DISTINCT o.signal.patternType FROM TradeOrder o " +
           "WHERE o.signal.patternType IS NOT NULL AND o.signal.patternType <> '' " +
           "ORDER BY o.signal.patternType")
    List<String> findDistinctPatterns();
}
