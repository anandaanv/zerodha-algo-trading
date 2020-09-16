package com.dtech.kitecon.data;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.util.Date;

@MappedSuperclass
@Data
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"timestamp", "instrument"}))
public class BaseCandle {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private long id;
    @Column
    protected Double open;
    @Column
    protected Double high;
    @Column
    protected Double low;
    @Column
    protected Double close;
    @Column
    protected Long volume;
    @Column
    protected Long oi;
    @Column
    protected LocalDateTime timestamp;
    @OneToOne
    protected Instrument instrument;


    public BaseCandle(Double open, Double high, Double low, Double close, Long volume, Long oi, LocalDateTime timestamp, Instrument instrument) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.oi = oi;
        this.timestamp = timestamp;
        this.instrument = instrument;
    }
}
