package com.dtech.aitrader.repository;

import com.dtech.aitrader.data.FiringOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Walk-forward outcome records keyed by their firing id. One row per firing; re-scoring with a
 * different {@code windowBars} replaces the existing row (JPA upsert via save+merge).
 */
@Repository
public interface FiringOutcomeRepository extends JpaRepository<FiringOutcome, String> {
}
