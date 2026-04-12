package com.dtech.kitecon.patternscanner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PatternScreenerRunResultRepository extends JpaRepository<PatternScreenerRunResult, Long> {
    List<PatternScreenerRunResult> findByRunIdOrderByScannedAt(Long runId);
}
