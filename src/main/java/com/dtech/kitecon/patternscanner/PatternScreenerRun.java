package com.dtech.kitecon.patternscanner;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "pattern_screener_run")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PatternScreenerRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screener_id", nullable = false)
    private Long screenerId;

    @Column(length = 32, nullable = false)
    private String status; // RUNNING, COMPLETED, FAILED

    @Builder.Default @Column(name = "total_symbols", nullable = false)
    private int totalSymbols = 0;

    @Builder.Default @Column(name = "processed_symbols", nullable = false)
    private int processedSymbols = 0;

    @Builder.Default @Column(name = "signals_created", nullable = false)
    private int signalsCreated = 0;

    @Builder.Default @Column(name = "duplicates_skipped", nullable = false)
    private int duplicatesSkipped = 0;

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

    @PrePersist public void prePersist() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate  public void preUpdate()  { updatedAt = Instant.now(); }
}
