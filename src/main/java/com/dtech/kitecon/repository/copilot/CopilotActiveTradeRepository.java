package com.dtech.kitecon.repository.copilot;

import com.dtech.kitecon.data.copilot.CopilotActiveTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CopilotActiveTradeRepository extends JpaRepository<CopilotActiveTrade, Long> {
    List<CopilotActiveTrade> findByInvestigationIdAndStateIn(Long investigationId, List<String> states);
    List<CopilotActiveTrade> findByHypothesisId(Long hypothesisId);
    List<CopilotActiveTrade> findBySymbolAndStateIn(String symbol, List<String> states);
    List<CopilotActiveTrade> findByIsOverrideTradeTrue();
}
