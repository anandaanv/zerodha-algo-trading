package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.StockAnalysisCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface StockAnalysisCacheRepository extends JpaRepository<StockAnalysisCache, Long> {

    /**
     * Find cached analysis by symbol and type
     */
    Optional<StockAnalysisCache> findBySymbolAndAnalysisType(String symbol, String analysisType);

    /**
     * Find cached analysis by symbol, timeframe, and type
     */
    Optional<StockAnalysisCache> findBySymbolAndTimeframeAndAnalysisType(
        String symbol,
        String timeframe,
        String analysisType
    );

    /**
     * Find valid (non-expired) cache entry
     */
    @Query("SELECT c FROM StockAnalysisCache c WHERE c.symbol = :symbol " +
           "AND c.analysisType = :analysisType " +
           "AND c.expiresAt > :now")
    Optional<StockAnalysisCache> findValidCache(
        @Param("symbol") String symbol,
        @Param("analysisType") String analysisType,
        @Param("now") LocalDateTime now
    );

    /**
     * Delete expired cache entries
     */
    @Modifying
    @Query("DELETE FROM StockAnalysisCache c WHERE c.expiresAt < :now")
    void deleteExpiredCache(@Param("now") LocalDateTime now);

    /**
     * Delete all cache entries for a symbol
     */
    void deleteBySymbol(String symbol);

    /**
     * Delete cache entry by symbol and type
     */
    void deleteBySymbolAndAnalysisType(String symbol, String analysisType);
}
