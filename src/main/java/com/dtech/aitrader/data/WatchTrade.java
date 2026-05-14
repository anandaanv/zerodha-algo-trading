package com.dtech.aitrader.data;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "watch_trade")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchTrade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "generated_for_date", nullable = false)
    private LocalDate generatedForDate;

    @Column(length = 8)
    private String direction;

    @Column(precision = 12, scale = 2)
    private BigDecimal entry;

    @Column(precision = 12, scale = 2)
    private BigDecimal sl;

    @Column(precision = 12, scale = 2)
    private BigDecimal target;

    private Double rr;

    private Double confidence;

    @Column(name = "trigger_type", length = 32)
    private String triggerType;

    @Column(name = "trigger_spec_json", columnDefinition = "TEXT")
    private String triggerSpecJson;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "triggered_at")
    private LocalDateTime triggeredAt;

    @Column(name = "triggered_price", precision = 12, scale = 2)
    private BigDecimal triggeredPrice;

    @Column(name = "validity_until")
    private LocalDateTime validityUntil;

    @Column(name = "agent_decision_id")
    private Long agentDecisionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
