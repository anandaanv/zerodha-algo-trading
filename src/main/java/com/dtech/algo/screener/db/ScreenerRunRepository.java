package com.dtech.algo.screener.db;

import com.dtech.algo.screener.enums.SchedulingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScreenerRunRepository extends JpaRepository<ScreenerRunEntity, Long> {
    List<ScreenerRunEntity> findByScreenerId(Long screenerId);
    List<ScreenerRunEntity> findBySymbol(String symbol);
    List<ScreenerRunEntity> findBySchedulingStatus(SchedulingStatus status);
}
