package com.dtech.aitrader.data;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_trade_replay")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AiTradeReplay {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "requested_at", nullable = false) private LocalDateTime requestedAt;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "source_simulation_trade_id") private Long sourceSimulationTradeId;
    @Column(name = "source_run_id_fk") private Long sourceRunIdFk;
    @Column(length = 32, nullable = false) private String symbol;
    @Column(name = "signal_time") private LocalDateTime signalTime;
    @Column(name = "original_direction", length = 8) private String originalDirection;
    @Column(name = "original_entry", precision = 12, scale = 2) private BigDecimal originalEntry;
    @Column(name = "original_sl", precision = 12, scale = 2) private BigDecimal originalSl;
    @Column(name = "original_target", precision = 12, scale = 2) private BigDecimal originalTarget;
    @Column(name = "original_exit_reason", length = 16) private String originalExitReason;
    @Column(name = "original_pnl_pct") private Double originalPnlPct;
    @Column(name = "ai_verdict", length = 16) private String aiVerdict;
    @Column(name = "ai_direction", length = 8) private String aiDirection;
    @Column(name = "ai_entry", precision = 12, scale = 2) private BigDecimal aiEntry;
    @Column(name = "ai_sl", precision = 12, scale = 2) private BigDecimal aiSl;
    @Column(name = "ai_target", precision = 12, scale = 2) private BigDecimal aiTarget;
    @Column(name = "ai_confidence") private Double aiConfidence;
    @Column(name = "ai_reasoning", columnDefinition = "TEXT") private String aiReasoning;
    @Column(name = "levels_input_tokens") private Integer levelsInputTokens;
    @Column(name = "levels_output_tokens") private Integer levelsOutputTokens;
    @Column(name = "pattern_input_tokens") private Integer patternInputTokens;
    @Column(name = "pattern_output_tokens") private Integer patternOutputTokens;
    @Column(name = "total_cost_usd", precision = 8, scale = 4) private BigDecimal totalCostUsd;
    @Column(name = "model_used", length = 64) private String modelUsed;
    @Column(name = "duration_ms") private Long durationMs;
}
