package com.dtech.kitecon.trade.repository;

import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.enums.TradingSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SegmentConfigRepository extends JpaRepository<SegmentConfig, Long> {
    List<SegmentConfig> findBySymbolAndEnabledTrue(String symbol);
    List<SegmentConfig> findAllByOrderBySymbolAscSegmentAsc();
    Optional<SegmentConfig> findBySymbolAndSegment(String symbol, TradingSegment segment);
}
