package com.dtech.algo.screener.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScreenerRepository extends JpaRepository<Screener, Long> {
}
