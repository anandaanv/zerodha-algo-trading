package com.dtech.kitecon.simulation.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SimulationRunRepository extends JpaRepository<SimulationRun, Long> {
    Optional<SimulationRun> findByRunId(String runId);
    List<SimulationRun> findAllByOrderByCreatedAtDesc();
}
