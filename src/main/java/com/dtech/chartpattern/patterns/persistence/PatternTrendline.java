package com.dtech.chartpattern.patterns.persistence;

import com.dtech.algo.series.Interval;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "pattern_trendline", indexes = {
        @Index(name = "idx_pt_symbol_interval", columnList = "trading_symbol,timeframe")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatternTrendline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_symbol", nullable = false)
    private String tradingSymbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "timeframe", nullable = false)
    private Interval interval;

    // Identifier to link multiple lines belonging to the same detected pattern instance
    @Column(name = "group_id", nullable = false, length = 64)
    private String groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "pattern_type", nullable = false, length = 40)
    private PatternType patternType;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 20)
    private PatternLineSide side;

    // Line segment endpoints (by index/time and value)
    @Column(name = "start_idx", nullable = false)
    private int startIdx;

    @Column(name = "end_idx", nullable = false)
    private int endIdx;

    @Column(name = "start_ts", nullable = false)
    private LocalDateTime startTs;

    @Column(name = "end_ts", nullable = false)
    private LocalDateTime endTs;

    @Column(name = "y1", nullable = false)
    private double y1;

    @Column(name = "y2", nullable = false)
    private double y2;

    // Linear model y = slope * idx + intercept (for convenience/backtesting)
    @Column(name = "slope_per_bar", nullable = false)
    private double slopePerBar;

    @Column(name = "intercept", nullable = false)
    private double intercept;

    // Confidence score [0..1]
    @Column(name = "confidence", nullable = false)
    private double confidence;

    // Optional metadata for debugging/analysis
    @Lob
    @Column(name = "metadata_json", columnDefinition = "LONGTEXT")
    private String metadataJson;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum PatternType {
        TRIANGLE_CONTRACTING,
        TRIANGLE_EXPANDING,
        WEDGE_ASCENDING,
        WEDGE_DESCENDING,
        REVERSAL_RESISTANCE,
        REVERSAL_SUPPORT,
        HNS_NECKLINE,
        DOUBLE_TOP_NECKLINE,
        DOUBLE_BOTTOM_NECKLINE
    }

    public enum PatternLineSide {
        UPPER,
        LOWER,
        NECKLINE,
        RESISTANCE,
        SUPPORT
    }
}
