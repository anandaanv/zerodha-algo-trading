package com.dtech.aitrader.repository;

import com.dtech.aitrader.data.AiLevelsSuppressed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiLevelsSuppressedRepository extends JpaRepository<AiLevelsSuppressed, Long> {
    List<AiLevelsSuppressed> findBySymbol(String symbol);
}
