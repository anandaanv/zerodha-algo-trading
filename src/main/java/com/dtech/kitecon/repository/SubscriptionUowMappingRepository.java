package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.SubscriptionUowMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubscriptionUowMappingRepository extends JpaRepository<SubscriptionUowMapping, Long> {

    List<SubscriptionUowMapping> findBySubscriptionId(Long subscriptionId);

    List<SubscriptionUowMapping> findBySubscriptionUowId(Long subscriptionUowId);

    boolean existsBySubscriptionIdAndSubscriptionUowId(Long subscriptionId, Long subscriptionUowId);
}
