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

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface SubscriptionUowRepository extends JpaRepository<SubscriptionUow, Long> {
    List<SubscriptionUow> findByParentSubscriptionId(Long parentSubscriptionId);
    Optional<SubscriptionUow> findByParentSubscriptionIdAndTradingSymbolAndInterval(Long parentSubscriptionId, String tradingSymbol, Interval interval);

    List<SubscriptionUow> findTop2000ByStatusInAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
            Collection<SubscriptionUowStatus> statuses, Instant now);

    // Fire-and-forget: update LTP for UOWs matching a trading symbol and timeframe
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value =
            "UPDATE subscription_uow u " +
            "JOIN instrument i ON i.tradingsymbol = u.trading_symbol " +
            "JOIN candle c ON c.instrument_instrument_token = i.instrument_token " +
            "  AND c.timeframe = u.timeframe " +
            "  AND c.timestamp = (SELECT MAX(c2.timestamp) FROM candle c2 WHERE c2.instrument_instrument_token = i.instrument_token AND c2.timeframe = u.timeframe) " +
            "SET u.last_traded_price = c.close " +
            "WHERE u.trading_symbol = :tradingSymbol AND u.timeframe = :timeframe",
            nativeQuery = true)
    int updateUowLtpFromLatestCandle(@Param("tradingSymbol") String tradingSymbol,
                                     @Param("timeframe") String timeframe);
}
