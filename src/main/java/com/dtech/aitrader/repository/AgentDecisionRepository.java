package com.dtech.aitrader.repository;

import com.dtech.aitrader.data.AgentDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentDecisionRepository extends JpaRepository<AgentDecision, Long> {
    List<AgentDecision> findBySymbol(String symbol);
}
