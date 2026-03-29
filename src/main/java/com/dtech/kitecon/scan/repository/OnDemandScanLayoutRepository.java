package com.dtech.kitecon.scan.repository;

import com.dtech.kitecon.scan.entity.OnDemandScanLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OnDemandScanLayoutRepository extends JpaRepository<OnDemandScanLayout, Long> {
    List<OnDemandScanLayout> findByScanIdOrderByTabOrder(Long scanId);
    void deleteByScanId(Long scanId);
}
