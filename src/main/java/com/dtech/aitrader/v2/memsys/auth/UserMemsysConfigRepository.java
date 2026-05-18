package com.dtech.aitrader.v2.memsys.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserMemsysConfigRepository extends JpaRepository<UserMemsysConfig, Long> {

    Optional<UserMemsysConfig> findByUserIdAndActiveTrue(Long userId);

    Optional<UserMemsysConfig> findByUserId(Long userId);
}
