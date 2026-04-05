package com.dtech.wavelab.elliott.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "wle_triangle_run", indexes = {
        @Index(name = "idx_wle_triangle_user_created", columnList = "userId, createdAt"),
        @Index(name = "idx_wle_triangle_symbol_tf", columnList = "symbol, timeframe")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WleTriangleRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(nullable = false, length = 16)
    private String timeframe;

    @Column(nullable = false)
    private Integer candleCount;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(length = 32)
    private String proposerA;

    @Column(length = 32)
    private String proposerB;

    @Column(length = 32)
    private String evaluator;

    @Lob
    @Column
    private String inputSummaryJson;

    @Lob
    @Column
    private String proposerAOutputJson;

    @Lob
    @Column
    private String proposerBOutputJson;

    @Lob
    @Column
    private String evaluatorOutputJson;

    @Column(length = 64)
    private String finalTriangleType;

    @Column(length = 32)
    private String finalStatus;

    @Column
    private Double finalConfidence;

    @Column(length = 32)
    private String selectedSource;

    @Lob
    @Column
    private String finalReason;

    @Lob
    @Column
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
