package com.dtech.kitecon.config;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.historical.limits.LimitsKey;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HistoricalDateLimit {

  public int getDuration(String exchange, Interval interval) {
    int offset = interval.getOffset();
    if(offset <= 60) {
      return durationAsInt(60);
    } else if (offset < 60*15) {
      return durationAsInt(90);
    } else if (offset < 60*60) {
      return durationAsInt(180);
    } else if (offset <= 24 * 60 * 60) {
      return durationAsInt(365);
    } else {
      return durationAsInt(2000);
    }
  }

  private static int durationAsInt(int twoHundred) {
    return Long.valueOf(Duration.of(twoHundred, ChronoUnit.DAYS).toDays()).intValue();
  }

  public int getTotalAvailableDuration(String exchange, Interval interval) {
    int offset = interval.getOffset();
    if(offset <= 60) {
      return durationAsInt(60);
    } else if (offset < 60*15) {
      return durationAsInt(90);
    } else if (offset < 60*60) {
      return durationAsInt(180);
    } else if (offset <= 24 * 60 * 60) {
      return durationAsInt(365);
    } else {
      return durationAsInt(2000);
    }
  }

}
