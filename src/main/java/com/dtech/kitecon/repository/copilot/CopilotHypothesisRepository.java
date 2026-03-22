package com.dtech.kitecon.repository.copilot;

import com.dtech.kitecon.data.copilot.CopilotHypothesis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CopilotHypothesisRepository extends JpaRepository<CopilotHypothesis, Long> {
    List<CopilotHypothesis> findByInvestigationId(Long investigationId);
    List<CopilotHypothesis> findBySymbolAndStateIn(String symbol, List<String> states);
    List<CopilotHypothesis> findByInvestigationIdAndStateIn(Long investigationId, List<String> states);
}
