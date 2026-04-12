package com.dtech.kitecon.patternscanner;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "pattern_screener")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PatternScreener {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 64)
    private String segments; // comma-separated: "EQ,OPT"

    @Column(name = "watching_tf", nullable = false, length = 16)
    private String watchingTf; // e.g. "1h"

    @Column(name = "confirm_tf", nullable = false, length = 16)
    private String confirmTf; // e.g. "15m"

    @Column(name = "schedule_cron", nullable = false, length = 128)
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

    @PrePersist public void prePersist() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate  public void preUpdate()  { updatedAt = Instant.now(); }
}
