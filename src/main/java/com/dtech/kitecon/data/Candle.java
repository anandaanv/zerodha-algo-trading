package com.dtech.kitecon.data;

import java.time.LocalDateTime;

import com.dtech.algo.series.Interval;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"timeframe", "timestamp", "instrument"}))
public class Candle {

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
  @ManyToOne(targetEntity = Instrument.class)
  protected Instrument instrument;
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE)
  private long id;

  @Column
  @Enumerated(EnumType.STRING)
  private Interval timeframe;

  //private String timeFrame


  public Candle(Double open, Double high, Double low, Double close, Long volume, Long oi,
                LocalDateTime timestamp, Instrument instrument, Interval interval) {
    this.open = open;
    this.high = high;
    this.low = low;
    this.close = close;
    this.volume = volume;
    this.oi = oi;
    this.timestamp = timestamp;
    this.instrument = instrument;
    this.timeframe = interval;
  }
}
