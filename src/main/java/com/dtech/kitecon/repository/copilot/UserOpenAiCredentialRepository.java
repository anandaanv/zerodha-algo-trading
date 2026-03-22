package com.dtech.kitecon.repository.copilot;

import com.dtech.kitecon.data.copilot.UserOpenAiCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserOpenAiCredentialRepository extends JpaRepository<UserOpenAiCredential, Long> {
    Optional<UserOpenAiCredential> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
}
