package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.Subscription;

import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
 List<Subscription> findAllByLatestTimestampBeforeAndStatus(Instant time, String status);

 List<Subscription> findAllByLatestTimestampIsNullAndStatus(String status);


  // Helper to check if subscription already exists for a trading symbol
  boolean existsByTradingSymbol(String tradingSymbol);

  // Find subscription by trading symbol
  Optional<Subscription> findByTradingSymbol(String tradingSymbol);
}
