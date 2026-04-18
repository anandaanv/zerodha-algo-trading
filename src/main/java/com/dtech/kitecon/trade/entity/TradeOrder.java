package com.dtech.kitecon.trade.entity;

import com.dtech.kitecon.trade.enums.ExitReason;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeOrderStatus;
import com.dtech.kitecon.trade.enums.TradingSegment;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "trade_order")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id", nullable = false)
    private TradeSignal signal;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(nullable = false, length = 20)
    private String underlyingSymbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TradingSegment segment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TradeDirection direction;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private Integer lotSize;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal entryPrice;

    @Column(precision = 14, scale = 4)
    private BigDecimal exitPrice;

    @Column(nullable = false)
    private Instant entryTime;

    private Instant exitTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TradeOrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ExitReason exitReason;

    @Column(precision = 14, scale = 2)
    private BigDecimal realisedPnl;

    @Column(name = "underlying_entry_price", precision = 14, scale = 4)
    private BigDecimal underlyingEntryPrice;

    @Column(name = "underlying_exit_price", precision = 14, scale = 4)
    private BigDecimal underlyingExitPrice;

    @Column(name = "stop_loss", precision = 14, scale = 4)
    private BigDecimal stopLoss;

    @Column(name = "target", precision = 14, scale = 4)
    private BigDecimal target;

    @Column(nullable = false, length = 10)
    private String instrumentType;

    @Column(nullable = false)
    private Long instrumentToken;

    @Column(precision = 14, scale = 4)
    private BigDecimal strike;

    private LocalDate expiry;

    @Column(nullable = false)
    private boolean paperTrade;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
