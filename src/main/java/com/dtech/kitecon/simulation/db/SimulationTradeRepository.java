package com.dtech.kitecon.simulation.db;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SimulationTradeRepository extends JpaRepository<SimulationTrade, Long> {
    Page<SimulationTrade> findByRunIdOrderByEntryTime(Long runId, Pageable pageable);
    Optional<SimulationTrade> findByIdAndRunId(Long id, Long runId);
    long countByRunId(Long runId);
}
