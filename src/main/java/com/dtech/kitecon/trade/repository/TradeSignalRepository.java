package com.dtech.kitecon.trade.repository;

import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.TradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradeSignalRepository extends JpaRepository<TradeSignal, Long> {
    List<TradeSignal> findByStatusIn(List<TradeStatus> statuses);
    List<TradeSignal> findBySymbolAndStatusIn(String symbol, List<TradeStatus> statuses);
    List<TradeSignal> findByStatus(TradeStatus status);
}
