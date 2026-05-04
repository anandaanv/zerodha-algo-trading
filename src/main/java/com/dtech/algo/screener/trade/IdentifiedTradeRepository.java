package com.dtech.algo.screener.trade;

import com.dtech.algo.series.Interval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IdentifiedTradeRepository extends JpaRepository<IdentifiedTrade, Long>, JpaSpecificationExecutor<IdentifiedTrade> {

    Optional<IdentifiedTrade> findTopByScriptAndTimeframeAndSideAndOpenIsTrue(String script, Interval interval, String side);

    List<IdentifiedTrade> findAllByOpenIsTrue();

    @Query("SELECT DISTINCT t.screenerType FROM IdentifiedTrade t WHERE t.screenerType IS NOT NULL AND t.screenerType <> '' ORDER BY t.screenerType")
    List<String> findDistinctScreenerTypes();

    @Query("SELECT DISTINCT t.pattern FROM IdentifiedTrade t WHERE t.pattern IS NOT NULL AND t.pattern <> '' ORDER BY t.pattern")
    List<String> findDistinctPatterns();
}
