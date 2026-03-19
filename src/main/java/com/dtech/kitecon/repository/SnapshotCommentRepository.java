package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.SnapshotComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SnapshotCommentRepository extends JpaRepository<SnapshotComment, Long> {

    /**
     * Find all comments for a snapshot (paginated)
     */
    Page<SnapshotComment> findBySnapshotIdOrderByCreatedAtDesc(Long snapshotId, Pageable pageable);

    /**
     * Find all comments for a snapshot (list)
     */
    List<SnapshotComment> findBySnapshotIdOrderByCreatedAtDesc(Long snapshotId);

    /**
     * Count comments for a snapshot
     */
    long countBySnapshotId(Long snapshotId);

    /**
     * Find all comments by a user
     */
    Page<SnapshotComment> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);

    /**
     * Delete all comments for a snapshot
     */
    void deleteBySnapshotId(Long snapshotId);
}
