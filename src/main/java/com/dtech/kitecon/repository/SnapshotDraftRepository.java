package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.SnapshotDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SnapshotDraftRepository extends JpaRepository<SnapshotDraft, Long> {

    /**
     * Find all drafts for a user
     */
    List<SnapshotDraft> findByUsernameAndStatusOrderByLastModifiedAtDesc(String username, String status);

    /**
     * Find user's most recent draft for a symbol
     */
    Optional<SnapshotDraft> findTopByUsernameAndSymbolAndStatusOrderByLastModifiedAtDesc(
        String username,
        String symbol,
        String status
    );

    /**
     * Count user's active drafts
     */
    long countByUsernameAndStatus(String username, String status);

    /**
     * Delete old drafts (cleanup)
     */
    void deleteByStatusAndLastModifiedAtBefore(String status, LocalDateTime before);
}
