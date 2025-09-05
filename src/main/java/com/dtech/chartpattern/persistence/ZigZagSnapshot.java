package com.dtech.chartpattern.persistence;

import com.dtech.algo.series.Interval;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;

@Entity
@Table(name = "zigzag_snapshot",
       uniqueConstraints = @UniqueConstraint(columnNames = {"trading_symbol", "timeframe"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZigZagSnapshot {

    @Id
    @GeneratedValue(generator = "increment")
    @GenericGenerator(name = "increment", strategy = "org.hibernate.id.IncrementGenerator")
    private Long id;

    @Column(name = "trading_symbol", nullable = false)
    private String tradingSymbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "timeframe", nullable = false)
    private Interval interval;

    @Lob
    @Column(name = "pivots_json", nullable = false, columnDefinition = "LONGTEXT")
    private String pivotsJson;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
