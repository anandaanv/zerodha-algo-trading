package com.dtech.algo.screener.db;
import com.dtech.algo.screener.enums.SchedulingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ScreenerRunRepository extends JpaRepository<ScreenerRunEntity, Long> {
    List<ScreenerRunEntity> findByScreenerId(Long screenerId);
    List<ScreenerRunEntity> findBySymbol(String symbol);
    List<ScreenerRunEntity> findBySchedulingStatus(SchedulingStatus status);

    // Due runs: SCHEDULED and executeAt <= now
    List<ScreenerRunEntity> findTop200BySchedulingStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(
            SchedulingStatus status, Instant now);

    // Avoid duplicates for the same screener/symbol/timeframe/executeAt
    boolean existsByScreenerIdAndSymbolAndTimeframeAndExecuteAt(
            Long screenerId, String symbol, String timeframe, Instant executeAt);
}
