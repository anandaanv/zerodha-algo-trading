package com.dtech.aitrader.repository;

import com.dtech.aitrader.data.AiTraderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiTraderConfigRepository extends JpaRepository<AiTraderConfig, Long> {
    Optional<AiTraderConfig> findByConfigKey(String configKey);

    boolean existsByConfigKey(String configKey);
}
