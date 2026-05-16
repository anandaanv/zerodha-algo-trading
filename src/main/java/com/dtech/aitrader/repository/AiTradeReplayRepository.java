package com.dtech.aitrader.repository;

import com.dtech.aitrader.data.AiTradeReplay;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AiTradeReplayRepository extends JpaRepository<AiTradeReplay, Long> {
    List<AiTradeReplay> findBySourceSimulationTradeId(Long id);
    List<AiTradeReplay> findBySymbolOrderByRequestedAtDesc(String symbol);
}
