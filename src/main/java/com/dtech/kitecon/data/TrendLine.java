package com.dtech.kitecon.data;

import com.dtech.algo.series.Interval;
import com.dtech.ta.OHLC;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"instrument", "startTime", "endTime", "startOhlc", "endOhlc"}))
public class TrendLine {
    @Id
    @GeneratedValue
    private Long id;

    @Column
    private String instrument;
    @Column
    private LocalDateTime startTime;
    @Column
    private LocalDateTime endTime;
    @Column
    @Enumerated(EnumType.STRING)
    private OHLC startOhlc;
    @Column
    @Enumerated(EnumType.STRING)
    private OHLC endOhlc;
    @Column
    @Enumerated(EnumType.STRING)
    private Interval timeframe;
}
