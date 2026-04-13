package com.dtech.kitecon.patternscanner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PatternScreenerRunRepository extends JpaRepository<PatternScreenerRun, Long> {
    List<PatternScreenerRun> findTop10ByScreenerIdOrderByStartedAtDesc(Long screenerId);
    boolean existsByScreenerIdAndStatus(Long screenerId, String status);
}
