package com.dtech.aitrader.repository;

import com.dtech.aitrader.data.PaperTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperTradeRepository extends JpaRepository<PaperTrade, Long> {
    List<PaperTrade> findByStatus(String status);
    List<PaperTrade> findBySymbolAndStatus(String symbol, String status);
}
