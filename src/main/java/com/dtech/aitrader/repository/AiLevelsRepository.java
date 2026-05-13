package com.dtech.aitrader.repository;

import com.dtech.aitrader.data.AiLevels;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AiLevelsRepository extends JpaRepository<AiLevels, Long> {
    Optional<AiLevels> findFirstBySymbolOrderByGeneratedAtDesc(String symbol);

    Optional<AiLevels> findBySymbolAndTimeframeAndGeneratedForDate(String symbol, String timeframe, LocalDate generatedForDate);

    List<AiLevels> findBySymbolOrderByGeneratedAtDesc(String symbol);
}
