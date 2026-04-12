package com.dtech.kitecon.patternscanner;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "pattern_screener_run_result")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PatternScreenerRunResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "screener_id", nullable = false)
    private Long screenerId;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(nullable = false, length = 16)
    private String status; // NO_PATTERN, PATTERN_FOUND, SIGNAL_CREATED, DUPLICATE, FILTERED, ERROR

    @Column(name = "patterns_found", columnDefinition = "TEXT")
    private String patternsFound; // JSON array

    @Column(name = "signal_id")
    private Long signalId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "processing_ms")
    private Long processingMs;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;
}
