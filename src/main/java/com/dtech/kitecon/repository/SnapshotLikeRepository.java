package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.SnapshotLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SnapshotLikeRepository extends JpaRepository<SnapshotLike, Long> {

    /**
     * Find a like by snapshot ID and username
     */
    Optional<SnapshotLike> findBySnapshotIdAndUsername(Long snapshotId, String username);

    /**
     * Check if user has liked a snapshot
     */
    boolean existsBySnapshotIdAndUsername(Long snapshotId, String username);

    /**
     * Count likes for a snapshot
     */
    long countBySnapshotId(Long snapshotId);

    /**
     * Delete a like by snapshot ID and username
     */
    void deleteBySnapshotIdAndUsername(Long snapshotId, String username);

    /**
     * Delete all likes for a snapshot
     */
    void deleteBySnapshotId(Long snapshotId);
}
