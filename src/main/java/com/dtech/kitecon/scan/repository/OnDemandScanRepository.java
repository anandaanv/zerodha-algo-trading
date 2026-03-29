package com.dtech.kitecon.scan.repository;

import com.dtech.kitecon.scan.entity.OnDemandScan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface OnDemandScanRepository extends JpaRepository<OnDemandScan, Long> {
    long countByUserIdAndRequestedAtAfter(Long userId, Instant since);
    List<OnDemandScan> findByUserIdInOrderByRequestedAtDesc(List<Long> userIds);
}
