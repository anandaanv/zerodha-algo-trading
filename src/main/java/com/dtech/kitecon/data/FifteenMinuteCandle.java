package com.dtech.kitecon.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import java.util.Date;

@Entity
@Data
@NoArgsConstructor
public class FifteenMinuteCandle extends BaseCandle {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE)
  private long id;

  @Builder
  public FifteenMinuteCandle(Double open, Double high, Double low, Double close, long volume, long oi, Date timestamp, Instrument instrument) {
    super(open, high, low, close, volume, oi, timestamp, instrument);
  }
}
