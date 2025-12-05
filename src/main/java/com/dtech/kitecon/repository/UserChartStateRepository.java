package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.UserChartState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserChartStateRepository extends JpaRepository<UserChartState, Long> {

    /**
     * Return the most recent saved chart state for the given symbol+period (default layout)
     * @deprecated Use findBySymbolAndPeriodAndLayoutName instead
     */
    @Deprecated
    UserChartState findTopBySymbolAndPeriodOrderByCreatedAtDesc(String symbol, String period);

    /**
     * Find chart state by unique key: symbol + period + layout_name
     */
    UserChartState findBySymbolAndPeriodAndLayoutName(String symbol, String period, String layoutName);

    /**
     * Find all chart states for a given symbol and period (all layouts)
     */
    java.util.List<UserChartState> findBySymbolAndPeriod(String symbol, String period);
}
