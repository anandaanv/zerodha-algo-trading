package com.dtech.kitecon.trade.repository;

import com.dtech.kitecon.trade.entity.TradeExecution;
import com.dtech.kitecon.trade.entity.TradeSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TradeExecutionRepository extends JpaRepository<TradeExecution, Long> {
    Optional<TradeExecution> findBySignal(TradeSignal signal);
    Optional<TradeExecution> findBySignalId(Long signalId);
}
