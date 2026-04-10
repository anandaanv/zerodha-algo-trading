package com.dtech.kitecon.trade.entity;

import com.dtech.kitecon.trade.enums.ExitReason;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_execution", indexes = {
    @Index(name = "idx_te_signal_id", columnList = "signal_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id", nullable = false, unique = true)
    private TradeSignal signal;

    @Column(length = 50)
    private String entryOrderId;

    @Column(precision = 14, scale = 4)
    private BigDecimal entryPriceActual;

    private Instant entryTime;

    private Integer quantity;

    @Column(precision = 14, scale = 2)
    private BigDecimal notionalValue;

    @Column(precision = 14, scale = 2)
    private BigDecimal marginDeployed;

    @Column(length = 50)
    private String slOrderId;

    @Column(length = 50)
    private String targetOrderId;

    @Column(length = 50)
    private String exitOrderId;

    @Column(precision = 14, scale = 4)
    private BigDecimal exitPriceActual;

    private Instant exitTime;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ExitReason exitReason;

    @Column(precision = 14, scale = 2)
    private BigDecimal grossPnlInr;

    @Column(precision = 10, scale = 2)
    private BigDecimal brokerageInr;

    @Column(precision = 14, scale = 2)
    private BigDecimal netPnlInr;

    @Column(precision = 8, scale = 4)
    private BigDecimal netPnlPct;    // % of margin deployed

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}
