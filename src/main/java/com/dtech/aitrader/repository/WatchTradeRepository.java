package com.dtech.aitrader.repository;

import com.dtech.aitrader.data.WatchTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface WatchTradeRepository extends JpaRepository<WatchTrade, Long> {
    List<WatchTrade> findByStatus(String status);

    List<WatchTrade> findBySymbolAndStatus(String symbol, String status);

    List<WatchTrade> findBySymbolAndGeneratedForDateAndSourceType(String symbol, LocalDate date, String sourceType);
}
