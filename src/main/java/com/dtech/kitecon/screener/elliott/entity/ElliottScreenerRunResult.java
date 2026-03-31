package com.dtech.kitecon.screener.elliott.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "elliott_screener_run_result",
    indexes = {
        @Index(name = "idx_esrr_run_id", columnList = "run_id"),
        @Index(name = "idx_esrr_screener_id", columnList = "screener_id")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElliottScreenerRunResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "screener_id", nullable = false)
    private Long screenerId;

    @Column(nullable = false, length = 50)
    private String symbol;

    /** PASSED, SKIPPED, FAILED, ERROR */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "suggestion_id")
    private Long suggestionId;

    @Column(name = "processing_ms")
    private Long processingMs;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;
}
