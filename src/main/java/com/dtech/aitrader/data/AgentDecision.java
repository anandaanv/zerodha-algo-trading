package com.dtech.aitrader.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_decision")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDecision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 20)
    private String agentType;

    @Column(nullable = false, length = 32)
    private String patternSource;

    @Column(nullable = false, length = 128)
    private String patternSignalRef;

    @Column(nullable = false)
    private LocalDateTime decidedAt;

    @Column(length = 16)
    private String verdict;

    @Column(length = 8)
    private String direction;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal entry;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal sl;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal target;

    @Column(nullable = false)
    private Double confidence;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    @Column(length = 16)
    private String actualOutcome;

    private Double actualPnlPct;

    @Column(nullable = false)
    private Integer inputTokens;

    @Column(nullable = false)
    private Integer outputTokens;

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal costUsd;

    @Column(nullable = false, length = 64)
    private String modelUsed;
}
