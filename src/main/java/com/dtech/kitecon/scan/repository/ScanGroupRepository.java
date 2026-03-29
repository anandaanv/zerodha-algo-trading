package com.dtech.kitecon.scan.repository;

import com.dtech.kitecon.scan.entity.ScanGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScanGroupRepository extends JpaRepository<ScanGroup, Long> {
}
