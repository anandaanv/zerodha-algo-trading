package com.dtech.chartpattern.persistence;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.trendline.TrendlineSide;
import com.dtech.chartpattern.trendline.TrendlineState;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;

@Entity
@Table(name = "trendline_record",
       indexes = {
           @Index(name = "idx_sym_tf_state_score", columnList = "trading_symbol, timeframe, state, score"),
           @Index(name = "idx_tf_state_prox", columnList = "timeframe, state, proximity_bucket, score_bucket"),
           @Index(name = "idx_sym_tf_side_flat", columnList = "trading_symbol, timeframe, side, is_flat"),
           @Index(name = "idx_sym_tf_lasttouch", columnList = "trading_symbol, timeframe, last_touch_ts")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrendlineRecord {

    @Id
    @GeneratedValue(generator = "increment")
    @GenericGenerator(name = "increment", strategy = "org.hibernate.id.IncrementGenerator")
    private Long id;

    @Column(name = "trading_symbol", nullable = false)
    private String tradingSymbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "timeframe", nullable = false)
    private Interval timeframe;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false)
    private TrendlineSide side;

    // Line y = m*x + b, x is sequence/bar index
    @Column(name = "slope_per_bar", nullable = false)
    private double slopePerBar;

    @Column(name = "intercept", nullable = false)
    private double intercept;

    @Column(name = "start_seq", nullable = false)
    private long startSeq;

    @Column(name = "end_seq", nullable = false)
    private long endSeq;

    // Quality metrics
    @Column(name = "touches", nullable = false)
    private int touches;

    @Column(name = "span_bars", nullable = false)
    private int spanBars;

    @Column(name = "residual", nullable = false)
    private double residual;

    @Column(name = "breach_ratio", nullable = false)
    private double breachRatio;

    @Column(name = "containment_window", nullable = false)
    private double containmentWindow; // 0..1 fraction within tolerance in recent window

    @Column(name = "containment_total", nullable = false)
    private double containmentTotal;  // 0..1 fraction within tolerance overall

    @Column(name = "last_touch_ts")
    private LocalDateTime lastTouchTs;

    @Column(name = "score", nullable = false)
    private double score;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private TrendlineState state;

    @Column(name = "broken_at_ts")
    private LocalDateTime brokenAtTs;

    @Column(name = "retest_ts")
    private LocalDateTime retestTs;

    @Column(name = "proximity_now_pct", nullable = false)
    private double proximityNowPct;

    @Column(name = "angle_deg", nullable = false)
    private double angleDeg;

    @Column(name = "is_flat", nullable = false)
    private boolean isFlat;

    // Buckets to speed up searches
    @Column(name = "score_bucket", nullable = false)
    private short scoreBucket;

    @Column(name = "proximity_bucket", nullable = false)
    private short proximityBucket;

    @Column(name = "angle_bucket", nullable = false)
    private short angleBucket;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
