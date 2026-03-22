package com.dtech.kitecon.repository.copilot;

import com.dtech.kitecon.data.copilot.CopilotOrchestratorConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CopilotOrchestratorConfigRepository extends JpaRepository<CopilotOrchestratorConfig, Long> {
    Optional<CopilotOrchestratorConfig> findByUserId(Long userId);
}
