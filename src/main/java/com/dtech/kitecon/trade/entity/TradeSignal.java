package com.dtech.kitecon.trade.entity;

import com.dtech.kitecon.trade.enums.StrategyType;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeStatus;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_signal", indexes = {
    @Index(name = "idx_ts_status", columnList = "status"),
    @Index(name = "idx_ts_symbol", columnList = "symbol"),
    @Index(name = "idx_ts_signal_time", columnList = "signal_time")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    private Long instrumentToken;

    @Column(length = 10)
    private String exchange;           // NSE, NFO

    @Column(length = 10)
    private String instrumentType;     // EQ, FUT

    private java.time.LocalDate expiryDate;

    private Integer lotSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TradeDirection direction;  // LONG, SHORT

    @Column(length = 40)
    private String patternType;        // DOUBLE_BOTTOM, DOUBLE_TOP_FAILURE, etc.

    @Column(length = 10)
    private String confirmationType;   // C1, FAILURE

    @Column(precision = 14, scale = 4)
    private BigDecimal entryPrice;

    @Column(precision = 14, scale = 4)
    private BigDecimal stopLoss;

    @Column(precision = 14, scale = 4)
    private BigDecimal target;

    @Column(precision = 14, scale = 4)
    private BigDecimal neckline;

    @Column(precision = 14, scale = 4)
    private BigDecimal patternHeight;

    @Column(precision = 6, scale = 2)
    private BigDecimal rrRatio;

    @Column(precision = 4, scale = 4)
    private BigDecimal mlScore;

    private Boolean rsiDivergence;

    @Column(precision = 6, scale = 2)
    private BigDecimal dailyRsi;

    @Column(precision = 8, scale = 4)
    private BigDecimal stochRsiK;

    @Column(length = 30)
    private String timeframe;

    @Column(name = "signal_time")
    private Instant signalTime;

    @Column(name = "candle_time")
    private Instant candleTime;

    @Column(name = "entry_valid_until")
    private Instant entryValidUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TradeStatus status;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private StrategyType strategyType;  // defaults to DTB for backward compat

    @Column(precision = 14, scale = 4)
    private BigDecimal w1Size;  // Wave 1 absolute size (for impulse slab computation)

    @Column
    private Integer currentSlabIndex;  // Current locked slab level (-1 = no slab reached)

    @Column
    private Integer barsInTrade;

    @Column
    private Integer barsSinceLastSlabAdvance;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private boolean paperTrade;

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = Instant.now();
        if (status == null) status = TradeStatus.WATCHING_ENTRY;
        if (strategyType == null) strategyType = StrategyType.DTB;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
