package com.dtech.kitecon.repository.copilot;

import com.dtech.kitecon.data.copilot.CopilotObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CopilotObservationRepository extends JpaRepository<CopilotObservation, Long> {

    List<CopilotObservation> findByInvestigationId(Long investigationId);

    List<CopilotObservation> findByInvestigationIdAndPatternDetectedTrue(Long investigationId);
}
