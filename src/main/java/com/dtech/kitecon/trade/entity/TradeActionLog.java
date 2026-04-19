package com.dtech.kitecon.trade.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_action_log", indexes = {
    @Index(name = "idx_action_log_signal", columnList = "signal_id"),
    @Index(name = "idx_action_log_time", columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id", nullable = false)
    private TradeSignal signal;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "candle_time")
    private Instant candleTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TradeAction action;

    @Column(length = 20)
    private String symbol;

    @Column(precision = 14, scale = 4)
    private BigDecimal price;

    @Column(precision = 14, scale = 4)
    private BigDecimal stopLevel;

    @Column(precision = 14, scale = 4)
    private BigDecimal targetLevel;

    @Column
    private Integer slabIndex;

    @Column(precision = 14, scale = 4)
    private BigDecimal pnlPct;

    @Column(length = 200)
    private String detail;

    public enum TradeAction {
        SIGNAL_CREATED,
        ENTRY_WATCHING,
        ENTRY_TRIGGERED,
        ENTRY_FILLED,
        ENTRY_EXPIRED,
        ENTRY_REJECTED,
        STOP_UPDATED,
        TARGET_UPDATED,
        SLAB_HIT,
        STOP_HIT,
        TSL_HIT,
        TIMEOUT_EXIT,
        STALL_EXIT,
        TARGET_HIT,
        REVERSAL_EXIT,
        MANUAL_EXIT,
        ERROR
    }

    @PrePersist
    void prePersist() {
        if (timestamp == null) timestamp = Instant.now();
    }
}
