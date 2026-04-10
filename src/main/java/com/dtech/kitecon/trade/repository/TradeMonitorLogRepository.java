package com.dtech.kitecon.trade.repository;

import com.dtech.kitecon.trade.entity.TradeMonitorLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradeMonitorLogRepository extends JpaRepository<TradeMonitorLog, Long> {
    List<TradeMonitorLog> findBySignalIdOrderByCheckedAtDesc(Long signalId);
    List<TradeMonitorLog> findTop10BySignalIdOrderByCheckedAtDesc(Long signalId);
}
