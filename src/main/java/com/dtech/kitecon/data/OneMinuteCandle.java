package com.dtech.kitecon.data;

import java.time.LocalDateTime;
import javax.persistence.Entity;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
public class OneMinuteCandle extends BaseCandle {

  @Builder
  public OneMinuteCandle(Double open, Double high, Double low, Double close, long volume, long oi,
      LocalDateTime timestamp, Instrument instrument) {
    super(open, high, low, close, volume, oi, timestamp, instrument);
  }
}
