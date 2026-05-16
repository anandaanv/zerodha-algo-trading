package com.dtech.kitecon.simulation.db;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SimulationTradeRepository extends JpaRepository<SimulationTrade, Long> {
    Page<SimulationTrade> findByRunIdOrderByEntryTime(Long runId, Pageable pageable);
    Page<SimulationTrade> findByRunIdAndSymbolOrderByEntryTime(Long runId, String symbol, Pageable pageable);
    Optional<SimulationTrade> findByIdAndRunId(Long id, Long runId);
    long countByRunId(Long runId);

    @Query("SELECT DISTINCT t.run.id FROM SimulationTrade t WHERE t.symbol = :symbol")
    List<Long> findDistinctRunIdsBySymbol(@Param("symbol") String symbol);
}
