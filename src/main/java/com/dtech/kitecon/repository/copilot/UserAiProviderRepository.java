package com.dtech.kitecon.repository.copilot;

import com.dtech.kitecon.data.copilot.UserAiProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAiProviderRepository extends JpaRepository<UserAiProvider, Long> {

    List<UserAiProvider> findByUserIdOrderByCreatedAtAsc(Long userId);

    Optional<UserAiProvider> findByUserIdAndId(Long userId, Long id);

    Optional<UserAiProvider> findByUserIdAndActiveTrue(Long userId);

    boolean existsByUserId(Long userId);

    @Modifying
    @Transactional
    @Query("UPDATE UserAiProvider p SET p.active = false WHERE p.userId = :userId")
    void deactivateAll(Long userId);
}
