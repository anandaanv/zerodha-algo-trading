package com.dtech.aitrader.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_levels_suppressed")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiLevelsSuppressed {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 20)
    private String lineType;

    @Column(nullable = false)
    private Long anchorT0;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal anchorP0;

    @Column(nullable = false)
    private Long anchorT1;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal anchorP1;

    @Column(nullable = false)
    private LocalDateTime suppressedAt;

    @Column(length = 255)
    private String reason;
}
