package com.dtech.aitrader.repository;

import com.dtech.aitrader.data.PlanGroup;
import com.dtech.aitrader.data.PlanGroupState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PlanGroupRepository extends JpaRepository<PlanGroup, Long> {

    /** Active plan_groups for a (user, symbol) — primary lookup during a scan. */
    List<PlanGroup> findByUserIdAndSymbolAndState(Long userId, String symbol, PlanGroupState state);

    /** Used by the hourly sweeper to expire stale WATCHING groups. */
    List<PlanGroup> findByStateAndValidUntilBefore(PlanGroupState state, LocalDateTime cutoff);

    /** All currently WATCHING groups for a user (across symbols) — used by morning review aggregation. */
    List<PlanGroup> findByUserIdAndStateOrderByUpdatedAtDesc(Long userId, PlanGroupState state);
}
