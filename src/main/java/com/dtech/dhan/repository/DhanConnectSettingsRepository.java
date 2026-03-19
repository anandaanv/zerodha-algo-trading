package com.dtech.dhan.repository;

import com.dtech.dhan.persistence.DhanConnectSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DhanConnectSettingsRepository extends JpaRepository<DhanConnectSettings, Long> {
}
