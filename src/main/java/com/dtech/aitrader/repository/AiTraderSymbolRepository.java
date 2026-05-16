package com.dtech.aitrader.repository;

import com.dtech.aitrader.data.AiTraderSymbol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiTraderSymbolRepository extends JpaRepository<AiTraderSymbol, String> {
    List<AiTraderSymbol> findByAiLevelsEnabledTrue();
    List<AiTraderSymbol> findByPatternAgentEnabledTrue();
}
