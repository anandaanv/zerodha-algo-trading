package com.dtech.algo.screener.trade;

import com.dtech.algo.series.Interval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdentifiedTradeRepository extends JpaRepository<IdentifiedTrade, Long> {

    Optional<IdentifiedTrade> findTopByScriptAndTimeframeAndSideAndOpenIsTrue(String script, Interval interval, String side);
}
