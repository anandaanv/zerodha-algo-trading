package com.dtech.kitecon.trade.entity;

import com.dtech.kitecon.trade.enums.MonitorAction;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_monitor_log", indexes = {
    @Index(name = "idx_tml_signal_id", columnList = "signal_id"),
    @Index(name = "idx_tml_checked_at", columnList = "checked_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeMonitorLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signal_id", nullable = false)
    private TradeSignal signal;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(precision = 14, scale = 4)
    private BigDecimal currentPrice;

    @Column(precision = 14, scale = 2)
    private BigDecimal unrealisedPnlInr;

    @Column(precision = 8, scale = 4)
    private BigDecimal unrealisedPnlPct;

    @Column(length = 40)
    private String candlePatternDetected;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MonitorAction actionTaken;

    @Column(length = 300)
    private String actionDetail;

    @Column(length = 20)
    private String workerVersion;

    private Boolean dryRun;
}
