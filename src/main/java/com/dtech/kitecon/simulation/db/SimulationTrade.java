package com.dtech.kitecon.simulation.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(
    name = "simulation_trade",
    indexes = {
        @Index(columnList = "run_id_fk, symbol"),
        @Index(columnList = "run_id_fk, pnl_pct"),
        @Index(columnList = "run_id_fk, exit_reason")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SimulationTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id_fk", nullable = false)
    private SimulationRun run;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 32)
    private String patternType;

    @Column(nullable = false, length = 8)
    private String direction;

    @Column(nullable = false)
    private Integer entryBar;

    @Column(nullable = false)
    private Instant entryTime;

    @Column(nullable = false)
    private Double entryPrice;

    @Column(nullable = false)
    private Double stopInitial;

    @Column(nullable = false)
    private Double targetInitial;

    @Column(nullable = false)
    private Integer exitBar;

    @Column(nullable = false)
    private Instant exitTime;

    @Column(nullable = false)
    private Double exitPrice;

    @Column(nullable = false, length = 16)
    private String exitReason;

    @Column(nullable = false)
    private Double pnlPct;

    @Column(nullable = false)
    private Boolean wasWinner;

    @Column(nullable = false)
    private Integer holdingBars;

    @Column(columnDefinition = "LONGTEXT")
    private String patternPivots;

    @Column(columnDefinition = "LONGTEXT")
    private String triggerMeta;
}
