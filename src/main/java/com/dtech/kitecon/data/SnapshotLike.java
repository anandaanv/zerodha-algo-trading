package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SnapshotLike - Tracks likes on chart snapshots
 * Ensures one user can only like a snapshot once
 */
@Entity
@Table(name = "snapshot_like",
    uniqueConstraints = @UniqueConstraint(name = "unique_user_snapshot", columnNames = {"snapshot_id", "username"})
)
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SnapshotLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Reference to the snapshot
     */
    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    /**
     * Username who liked
     */
    @Column(nullable = false)
    private String username;

    /**
     * Timestamp when liked
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
