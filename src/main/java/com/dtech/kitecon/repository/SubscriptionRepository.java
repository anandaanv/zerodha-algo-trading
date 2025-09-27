package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.Subscription;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
  List<Subscription> findAllByLatestTimestampBeforeAndStatus(Instant time, String status);

  List<Subscription> findAllByLatestTimestampIsNullAndStatus(String status);

  // Helper to check if subscription already exists for a trading symbol
  boolean existsByTradingSymbol(String tradingSymbol);

  // Find subscription by trading symbol
  Optional<Subscription> findByTradingSymbol(String tradingSymbol);

  
  @Query(value =
          "SELECT c.close FROM subscription s " +
                  "JOIN instrument i ON i.tradingsymbol = s.trading_symbol " +
                  "JOIN candle c ON c.instrument_instrument_token = i.instrument_token " +
                  "  AND c.timeframe = :timeframe " +
                  "  AND c.timestamp = (SELECT MAX(c2.timestamp) FROM candle c2 WHERE c2.instrument_instrument_token = i.instrument_token AND c2.timeframe = :timeframe) " +
                  "WHERE s.trading_symbol = :tradingSymbol",
          nativeQuery = true)
  Double getLatestClosePriceFromCandle(@Param("tradingSymbol") String tradingSymbol,
                                       @Param("timeframe") String timeframe);
}
