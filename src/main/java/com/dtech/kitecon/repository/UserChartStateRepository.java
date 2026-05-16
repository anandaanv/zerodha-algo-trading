package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.UserChartState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserChartStateRepository extends JpaRepository<UserChartState, Long> {
    Optional<UserChartState> findBySymbolAndLayoutId(String symbol, Long layoutId);
    
    Optional<UserChartState> findBySymbolAndLayoutIdAndTimeframe(String symbol, Long layoutId, String timeframe);
    
    Optional<UserChartState> findBySymbolAndLayoutIdAndTimeframeIsNull(String symbol, Long layoutId);
}
