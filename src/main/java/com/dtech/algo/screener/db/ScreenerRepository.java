package com.dtech.algo.screener.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScreenerRepository extends JpaRepository<ScreenerEntity, Long> {
    // ScreenerEntity is assumed to have an 'active' flag
    List<ScreenerEntity> findByDeletedFalse();
}
