package com.dtech.algo.screener.trade;

import com.dtech.algo.series.Interval;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "identified_trades")
@Getter
@Setter
public class IdentifiedTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Script/symbol and timeframe interval text (e.g., 15m, 1h)
    @Column(nullable = false)
    private String script;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Interval timeframe;

    // BUY/SELL
    @Column(nullable = false)
    private String side;

    // Keep string to allow empty and later-populated values
    @Column
    private String entry;

    @Column
    private String target;

    @Column
    private String stoploss;

    @Column(name = "time_triggered", nullable = false)
    private Instant timeTriggered;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(columnDefinition = "TEXT")
    private String logs;

    @Column(nullable = false)
    private boolean open = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
