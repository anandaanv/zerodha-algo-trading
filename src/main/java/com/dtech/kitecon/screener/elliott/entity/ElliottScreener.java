package com.dtech.kitecon.screener.elliott.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "elliott_screener")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElliottScreener {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 255, nullable = false)
    private String name;

    @Lob
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String symbols;

    @Column(length = 255, nullable = false)
    private String timeframes;

    @Column(name = "primary_timeframe", length = 64)
    private String primaryTimeframe;

    @Column(name = "schedule_cron", length = 128, nullable = false)
    private String scheduleCron;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

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
