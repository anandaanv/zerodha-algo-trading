package com.dtech.chartpattern.persistence;

import com.dtech.algo.series.Interval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ZigZagSnapshotRepository extends JpaRepository<ZigZagSnapshot, Long> {
    Optional<ZigZagSnapshot> findByTradingSymbolAndInterval(String tradingSymbol, Interval interval);
}
