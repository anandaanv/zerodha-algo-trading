package com.dtech.kitecon.repository;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.SubscriptionUow;
import com.dtech.kitecon.enums.SubscriptionUowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionUowRepository extends JpaRepository<SubscriptionUow, Long> {
    List<SubscriptionUow> findByParentSubscriptionId(Long parentSubscriptionId);
    Optional<SubscriptionUow> findByParentSubscriptionIdAndTradingSymbolAndInterval(Long parentSubscriptionId, String tradingSymbol, Interval interval);

    List<SubscriptionUow> findTop2000ByStatusInAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
            Collection<SubscriptionUowStatus> statuses, Instant now);
}
