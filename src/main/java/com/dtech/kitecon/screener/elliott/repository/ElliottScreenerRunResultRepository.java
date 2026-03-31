package com.dtech.kitecon.screener.elliott.repository;

import com.dtech.kitecon.screener.elliott.entity.ElliottScreenerRunResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ElliottScreenerRunResultRepository extends JpaRepository<ElliottScreenerRunResult, Long> {

    List<ElliottScreenerRunResult> findByRunIdOrderByScannedAt(Long runId);

    List<ElliottScreenerRunResult> findByScreenerIdOrderByScannedAtDesc(Long screenerId);

    Optional<ElliottScreenerRunResult> findTopByScreenerIdAndSymbolOrderByScannedAtDesc(Long screenerId, String symbol);
}
