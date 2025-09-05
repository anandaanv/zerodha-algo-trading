package com.dtech.chartpattern.persistence;

import com.dtech.algo.series.Interval;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "zigzag_config",
       uniqueConstraints = @UniqueConstraint(columnNames = {"trading_symbol", "timeframe"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZigZagConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_symbol", nullable = false)
    private String tradingSymbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "timeframe", nullable = false)
    private Interval interval;

    @Column(name = "atr_length", nullable = false)
    private int atrLength;

    @Column(name = "atr_mult", nullable = false)
    private double atrMult;

    @Column(name = "pct_min", nullable = false)
    private double pctMin;

    @Column(name = "hysteresis", nullable = false)
    private double hysteresis;

    @Column(name = "min_bars_between_pivots", nullable = false)
    private int minBarsBetweenPivots;

    // Dynamic percent settings
    @Column(name = "dynamic_pct_enabled", nullable = false)
    private boolean dynamicPctEnabled = true;

    @Column(name = "vol_mult", nullable = false)
    private double volMult = 2.0;

    @Column(name = "rvol_window", nullable = false)
    private int rvolWindow = 50;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
