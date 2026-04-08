package com.dtech.kitecon.screener.elliott.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "elliott_trade_suggestion",
    indexes = {
        @Index(name = "idx_ets_screener_id", columnList = "screener_id"),
        @Index(name = "idx_ets_user_id", columnList = "user_id"),
        @Index(name = "idx_ets_symbol_state", columnList = "symbol, state"),
        @Index(name = "idx_ets_screener_symbol_dir", columnList = "screener_id, symbol, direction, state"),
        @Index(name = "idx_ets_user_state", columnList = "user_id, state"),
        @Index(name = "idx_ets_run_id", columnList = "run_id")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElliottTradeSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screener_id", nullable = true)
    private Long screenerId;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "source_scan_id")
    private Long sourceScanId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 128, nullable = false)
    private String symbol;

    @Column(name = "primary_timeframe", length = 64)
    private String primaryTimeframe;

    @Column(name = "all_timeframes", length = 255)
    private String allTimeframes;

    @Column(length = 16, nullable = false)
    private String direction;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private SuggestionState state;

    @Column(name = "hypothesis_label", length = 512)
    private String hypothesisLabel;

    @Column(name = "wave_context", length = 1024)
    private String waveContext;

    @Column(length = 512)
    private String pattern;

    @Column(name = "current_stage", length = 128)
    private String currentStage;

    @Column(name = "entry_zone", length = 256)
    private String entryZone;

    @Column(name = "stop_loss", length = 256)
    private String stopLoss;

    @Column(length = 256)
    private String target1;

    @Column(name = "entry_low", precision = 15, scale = 2)
    private java.math.BigDecimal entryLow;

    @Column(name = "entry_high", precision = 15, scale = 2)
    private java.math.BigDecimal entryHigh;

    @Column(name = "stop_loss_price", precision = 15, scale = 2)
    private java.math.BigDecimal stopLossPrice;

    @Column(name = "target1_price", precision = 15, scale = 2)
    private java.math.BigDecimal target1Price;

    @Column(name = "trigger_description", length = 1024)
    private String triggerDescription;

    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String reasoning;

    @Lob
    @Column(name = "confidence_layers_json", columnDefinition = "MEDIUMTEXT")
    private String confidenceLayersJson;

    @Lob
    @Column(name = "invalidation_conditions_json", columnDefinition = "MEDIUMTEXT")
    private String invalidationConditionsJson;

    @Lob
    @Column(name = "anomaly_flags_json", columnDefinition = "MEDIUMTEXT")
    private String anomalyFlagsJson;

    @Lob
    @Column(name = "raw_ai_response", columnDefinition = "MEDIUMTEXT")
    private String rawAiResponse;

    @Lob
    @Column(name = "user_notes", columnDefinition = "MEDIUMTEXT")
    private String userNotes;

    @Column(name = "proposed_at")
    private Instant proposedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (proposedAt == null) proposedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
