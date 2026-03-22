package com.dtech.kitecon.repository.copilot;

import com.dtech.kitecon.data.copilot.CopilotAnomalyFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CopilotAnomalyFlagRepository extends JpaRepository<CopilotAnomalyFlag, Long> {
    List<CopilotAnomalyFlag> findByInvestigationIdAndAcknowledgedFalse(Long investigationId);
    List<CopilotAnomalyFlag> findByInvestigationId(Long investigationId);
    List<CopilotAnomalyFlag> findByHypothesisId(Long hypothesisId);
}
