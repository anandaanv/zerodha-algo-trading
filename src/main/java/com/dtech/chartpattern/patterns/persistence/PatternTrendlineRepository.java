package com.dtech.chartpattern.patterns.persistence;

import com.dtech.algo.series.Interval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PatternTrendlineRepository extends JpaRepository<PatternTrendline, Long> {

    List<PatternTrendline> findAllByTradingSymbolAndInterval(String tradingSymbol, Interval interval);

    void deleteByTradingSymbolAndInterval(String tradingSymbol, Interval interval);
}
