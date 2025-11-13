package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.AppSecrets;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppSecretsRepository extends JpaRepository<AppSecrets, Long> {

  /**
   * Find all secrets for a given environment
   */
  List<AppSecrets> findByEnv(String env);

  /**
   * Find a specific secret by environment and key
   */
  Optional<AppSecrets> findByEnvAndPropKey(String env, String propKey);
}
