package com.dtech.kitecon.data;

import java.time.LocalDateTime;
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

  @Builder
  public FifteenMinuteCandle(Double open, Double high, Double low, Double close, long volume, long oi, LocalDateTime timestamp, Instrument instrument) {
    super(open, high, low, close, volume, oi, timestamp, instrument);
  }
}
