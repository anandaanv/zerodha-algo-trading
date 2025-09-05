package com.dtech.chartpattern.persistence;

import com.dtech.algo.series.Interval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ZigZagConfigRepository extends JpaRepository<ZigZagConfig, Long> {
    Optional<ZigZagConfig> findByTradingSymbolAndInterval(String tradingSymbol, Interval interval);
}
