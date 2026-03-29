package com.dtech.kitecon.screener.elliott.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "elliott_screener_run",
    indexes = {
        @Index(name = "idx_esr_screener_id", columnList = "screener_id"),
        @Index(name = "idx_esr_started_at", columnList = "started_at"),
        @Index(name = "idx_esr_status", columnList = "status")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElliottScreenerRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screener_id", nullable = false)
    private Long screenerId;

    @Column(length = 32, nullable = false)
    private String status;

    @Builder.Default
    @Column(name = "total_symbols", nullable = false)
    private int totalSymbols = 0;

    @Builder.Default
    @Column(name = "processed_symbols", nullable = false)
    private int processedSymbols = 0;

    @Builder.Default
    @Column(name = "suggestions_created", nullable = false)
    private int suggestionsCreated = 0;

    @Builder.Default
    @Column(name = "duplicates_skipped", nullable = false)
    private int duplicatesSkipped = 0;

    @Lob
    @Column(name = "error_summary", columnDefinition = "MEDIUMTEXT")
    private String errorSummary;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
