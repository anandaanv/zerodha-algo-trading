package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SnapshotComment - Comments on public chart snapshots
 */
@Entity
@Table(name = "snapshot_comment", indexes = {
    @Index(name = "idx_snapshot_id", columnList = "snapshot_id"),
    @Index(name = "idx_username", columnList = "username")
})
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SnapshotComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Reference to the snapshot
     */
    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    /**
     * Username of the commenter
     */
    @Column(nullable = false)
    private String username;

    /**
     * Comment text
     */
    @Lob
    @Column(name = "comment_text", nullable = false, columnDefinition = "TEXT")
    private String commentText;

    /**
     * Timestamp when created
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
