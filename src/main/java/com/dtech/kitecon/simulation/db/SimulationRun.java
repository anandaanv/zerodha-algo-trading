package com.dtech.kitecon.simulation.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "simulation_run")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SimulationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String runId;

    @Column(nullable = false, length = 255)
    private String strategyName;

    @Column(nullable = false, length = 32)
    private String timeframe;

    @Column(nullable = false)
    private Integer stocksCount;

    @Column(nullable = false)
    private Integer totalTrades;

    @Column(nullable = false)
    private Integer wins;

    @Column(nullable = false)
    private Integer losses;

    @Column(nullable = false)
    private Double totalPnlPct;

    @Column(columnDefinition = "LONGTEXT")
    private String extraMeta;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
