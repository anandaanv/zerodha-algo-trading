package com.dtech.kitecon.data;

import java.time.Instant;
import java.time.LocalDateTime;

import com.dtech.algo.series.Interval;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.PartitionKey;

@Entity
@Data
@NoArgsConstructor
@Table(
        uniqueConstraints = @UniqueConstraint(columnNames = {"timeframe", "timestamp", "instrument_instrument_token"}),
        indexes = {
                @Index(columnList = "instrument_instrument_token, timeframe"),
                @Index(name = "idx_candle_inst_tf_ts", columnList = "instrument_instrument_token, timeframe, timestamp")
        }
)
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

  @EqualsAndHashCode.Include
  protected Instant timestamp;
  @ManyToOne(targetEntity = Instrument.class)

  @EqualsAndHashCode.Include
  @PartitionKey
  protected Instrument instrument;

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE)
  private long id;

  @Column
  @Enumerated(EnumType.STRING)
  @EqualsAndHashCode.Include
  private Interval timeframe;

  //private String timeFrame


  public Candle(Double open, Double high, Double low, Double close, Long volume, Long oi,
                Instant timestamp, Instrument instrument, Interval interval) {
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
