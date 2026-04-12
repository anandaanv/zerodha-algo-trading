package com.dtech.kitecon.patternscanner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PatternScreenerRepository extends JpaRepository<PatternScreener, Long> {
    List<PatternScreener> findByUserIdOrderByCreatedAtDesc(Long userId);
}
