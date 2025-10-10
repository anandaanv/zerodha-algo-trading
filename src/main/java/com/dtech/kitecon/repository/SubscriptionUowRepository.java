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

    Optional<SubscriptionUow> findByTradingSymbolAndTimeframe(String tradingSymbol, Interval timeframe);

    List<SubscriptionUow> findByStatusInAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
            Collection<SubscriptionUowStatus> statuses, Instant now);


    List<SubscriptionUow> findAllByStatusIn(Collection<SubscriptionUowStatus> statuses);


    @Query(value =
            "SELECT c.close FROM subscription_uow u " +
                    "JOIN instrument i ON i.tradingsymbol = u.trading_symbol " +
                    "JOIN candle c ON c.instrument_instrument_token = i.instrument_token " +
                    "  AND c.timeframe = u.timeframe " +
                    "  AND c.timestamp = (SELECT MAX(c2.timestamp) FROM candle c2 WHERE c2.instrument_instrument_token = i.instrument_token AND c2.timeframe = u.timeframe) " +
                    "WHERE u.trading_symbol = :tradingSymbol AND u.timeframe = :timeframe " +
                    "LIMIT 1",
            nativeQuery = true)
    Double getLatestCandleClose(@Param("tradingSymbol") String tradingSymbol,
                                @Param("timeframe") String timeframe);
}
