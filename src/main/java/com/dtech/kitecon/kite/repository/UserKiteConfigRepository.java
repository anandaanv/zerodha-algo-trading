package com.dtech.kitecon.kite.repository;

import com.dtech.kitecon.kite.entity.UserKiteConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserKiteConfigRepository extends JpaRepository<UserKiteConfig, Long> {
    List<UserKiteConfig> findByPlatformUserId(Long platformUserId);
    List<UserKiteConfig> findByActiveTrue();
}
