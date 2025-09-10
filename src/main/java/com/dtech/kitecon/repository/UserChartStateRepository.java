package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.UserChartState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserChartStateRepository extends JpaRepository<UserChartState, Long> {

    /**
     * Return the most recent saved chart state for the given symbol+period
     */
    UserChartState findTopBySymbolAndPeriodOrderByCreatedAtDesc(String symbol, String period);
}
