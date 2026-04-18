package com.dtech.kitecon.trade.repository;

import com.dtech.kitecon.trade.entity.TradeActionLog;
import com.dtech.kitecon.trade.entity.TradeSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TradeActionLogRepository extends JpaRepository<TradeActionLog, Long> {
    List<TradeActionLog> findBySignalOrderByTimestampAsc(TradeSignal signal);
    List<TradeActionLog> findBySignalIdOrderByTimestampAsc(Long signalId);
    List<TradeActionLog> findBySymbolOrderByTimestampDesc(String symbol);
}
