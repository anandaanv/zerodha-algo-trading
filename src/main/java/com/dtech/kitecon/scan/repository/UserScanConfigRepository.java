package com.dtech.kitecon.scan.repository;

import com.dtech.kitecon.scan.entity.UserScanConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserScanConfigRepository extends JpaRepository<UserScanConfig, Long> {
}
