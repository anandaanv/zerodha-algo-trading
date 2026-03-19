package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.UserSubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserSubscriptionPlanRepository extends JpaRepository<UserSubscriptionPlan, Long> {

    /**
     * Find subscription plan by username
     */
    Optional<UserSubscriptionPlan> findByUsername(String username);

    /**
     * Check if user has an active subscription
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END " +
           "FROM UserSubscriptionPlan u WHERE u.username = :username " +
           "AND (u.validUntil IS NULL OR u.validUntil > :now)")
    boolean hasActiveSubscription(
        @Param("username") String username,
        @Param("now") LocalDateTime now
    );

    /**
     * Find expired subscriptions
     */
    @Query("SELECT u FROM UserSubscriptionPlan u WHERE u.validUntil IS NOT NULL " +
           "AND u.validUntil < :now AND u.planType != 'FREE'")
    Iterable<UserSubscriptionPlan> findExpiredSubscriptions(@Param("now") LocalDateTime now);
}
