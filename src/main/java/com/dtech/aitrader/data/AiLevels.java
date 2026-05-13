package com.dtech.aitrader.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_levels", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"symbol", "timeframe", "generated_for_date"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiLevels {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 20)
    private String timeframe;

    @Column(nullable = false)
    private LocalDateTime generatedAt;

    @Column(nullable = false)
    private LocalDate generatedForDate;

    @Column(columnDefinition = "LONGTEXT")
    private String levelsJson;

    @Column(nullable = false)
    private Integer inputTokens;

    @Column(nullable = false)
    private Integer outputTokens;

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal costUsd;

    @Column(nullable = false, length = 64)
    private String modelUsed;
}
