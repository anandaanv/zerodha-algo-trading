package com.dtech.chartpattern.persistence;

import com.dtech.algo.series.Interval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface TrendlineRepository extends JpaRepository<TrendlineRecord, Long>, JpaSpecificationExecutor<TrendlineRecord> {
    void deleteByTradingSymbolAndTimeframe(String tradingSymbol, Interval timeframe);
    List<TrendlineRecord> findByTradingSymbolAndTimeframe(String tradingSymbol, Interval timeframe);
}
