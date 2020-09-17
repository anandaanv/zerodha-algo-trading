package com.dtech.kitecon.data;

import java.time.LocalDateTime;
import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

@MappedSuperclass
@Data
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"timestamp", "instrument"}))
public class BaseCandle {

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
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE)
  private long id;


  public BaseCandle(Double open, Double high, Double low, Double close, Long volume, Long oi,
      LocalDateTime timestamp, Instrument instrument) {
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
