package com.dtech.kitecon.config;

import com.dtech.kitecon.historical.limits.LimitsKey;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HistoricalDateLimit {

  private Map<LimitsKey, Duration> durationMap = new HashMap<>();
  private Map<LimitsKey, Duration> availableDuration = new HashMap<>();

  @PostConstruct
  public void initialize() {
    durationMap.put(limits("NSE", "15minute"), Duration.ofDays(50));
    durationMap.put(limits("NFO", "15minute"), Duration.ofDays(50));
    durationMap.put(limits("NSE", "day"), Duration.ofDays(600));
    durationMap.put(limits("NFO", "day"), Duration.ofDays(600));
    durationMap.put(limits("NSE", "5minute"), Duration.ofDays(15));
    durationMap.put(limits("NFO", "5minute"), Duration.ofDays(15));
    durationMap.put(limits("NSE", "minute"), Duration.ofDays(10));
    durationMap.put(limits("NFO", "minute"), Duration.ofDays(10));
  }

  @PostConstruct
  public void initializeStartDate() {
    availableDuration.put(limits("NSE", "15minute"), Duration.of(800, ChronoUnit.DAYS));
    availableDuration.put(limits("NFO", "15minute"), Duration.of(800, ChronoUnit.DAYS));
    availableDuration.put(limits("NSE", "day"), Duration.of(800, ChronoUnit.DAYS));
    availableDuration.put(limits("NFO", "day"), Duration.of(800, ChronoUnit.DAYS));
    availableDuration.put(limits("NSE", "5minute"), Duration.of(800, ChronoUnit.DAYS));
    availableDuration.put(limits("NFO", "5minute"), Duration.of(800, ChronoUnit.DAYS));
    availableDuration.put(limits("NSE", "minute"), Duration.of(200, ChronoUnit.DAYS));
    availableDuration.put(limits("NFO", "minute"), Duration.of(200, ChronoUnit.DAYS));
  }

  private LimitsKey limits(String exchange, String interval) {
    return LimitsKey.builder().exchange(exchange).interval(interval).build();
  }

  public int getDuration(String exchange, String interval) {
    LimitsKey key = LimitsKey.builder()
        .exchange(exchange)
        .interval(interval)
        .build();
    return Long.valueOf(durationMap.get(key).toDays()).intValue();
  }

  public int getTotalAvailableDuration(String exchange, String interval) {
    LimitsKey key = LimitsKey.builder()
        .exchange(exchange)
        .interval(interval)
        .build();
    return Long.valueOf(availableDuration.get(key).toDays()).intValue();
  }

}
