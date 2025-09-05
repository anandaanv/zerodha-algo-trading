package com.dtech.kitecon.repository;

import com.dtech.kitecon.persistence.KiteConnectSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KiteConnectSettingsRepository extends JpaRepository<KiteConnectSettings, Long> {
}
