package com.dtech.kitecon.scan.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "on_demand_scans")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnDemandScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 20)
    private String primaryTimeframe;

    @Column(length = 255)
    private String allTimeframes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScanStatus status;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(length = 500)
    private String errorMessage;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "verbose_logging", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    @Builder.Default
    private boolean verboseLogging = false;

    @PrePersist
    protected void onCreate() {
        requestedAt = Instant.now();
    }
}
