package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.ChartSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChartSnapshotRepository extends JpaRepository<ChartSnapshot, Long> {

    /**
     * Find all snapshots for a specific user
     */
    Page<ChartSnapshot> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);

    /**
     * Find a specific snapshot by ID and username (for security)
     */
    Optional<ChartSnapshot> findByIdAndUsername(Long id, String username);

    /**
     * Find all public snapshots
     */
    Page<ChartSnapshot> findByVisibilityOrderByCreatedAtDesc(
        ChartSnapshot.SnapshotVisibility visibility,
        Pageable pageable
    );

    /**
     * Find public snapshots by symbol
     */
    Page<ChartSnapshot> findBySymbolAndVisibilityOrderByCreatedAtDesc(
        String symbol,
        ChartSnapshot.SnapshotVisibility visibility,
        Pageable pageable
    );

    /**
     * Find public snapshots by pattern type
     */
    Page<ChartSnapshot> findByPatternTypeAndVisibilityOrderByCreatedAtDesc(
        String patternType,
        ChartSnapshot.SnapshotVisibility visibility,
        Pageable pageable
    );

    /**
     * Find public snapshots by symbol and pattern type
     */
    Page<ChartSnapshot> findBySymbolAndPatternTypeAndVisibilityOrderByCreatedAtDesc(
        String symbol,
        String patternType,
        ChartSnapshot.SnapshotVisibility visibility,
        Pageable pageable
    );

    /**
     * Count private snapshots for a user (for subscription limit checking)
     */
    long countByUsernameAndVisibility(
        String username,
        ChartSnapshot.SnapshotVisibility visibility
    );

    /**
     * Find top snapshots by likes
     */
    Page<ChartSnapshot> findByVisibilityOrderByLikesCountDesc(
        ChartSnapshot.SnapshotVisibility visibility,
        Pageable pageable
    );

    /**
     * Search public snapshots by symbol with filters
     */
    @Query("SELECT s FROM ChartSnapshot s WHERE s.visibility = :visibility " +
           "AND (:symbol IS NULL OR s.symbol = :symbol) " +
           "AND (:patternType IS NULL OR s.patternType = :patternType) " +
           "ORDER BY s.createdAt DESC")
    Page<ChartSnapshot> searchPublicSnapshots(
        @Param("visibility") ChartSnapshot.SnapshotVisibility visibility,
        @Param("symbol") String symbol,
        @Param("patternType") String patternType,
        Pageable pageable
    );

    /**
     * Find all snapshots by symbol (for summary generation)
     */
    Page<ChartSnapshot> findBySymbolOrderByCreatedAtDesc(String symbol, Pageable pageable);

    /**
     * Count snapshots by symbol
     */
    long countBySymbol(String symbol);
}
