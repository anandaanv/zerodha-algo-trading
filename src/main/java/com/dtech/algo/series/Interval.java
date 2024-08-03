package com.dtech.algo.series;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum Interval {
  OneMinute("minute", 60),
  ThreeMinute("3minute", 3*60),
  FiveMinute("5minute", 5*60),
  FifteenMinute("15minute", 15*60),
  ThirtyMinute("30minute", 30 * 60),
  OneHour("60minute", 60 * 60),
  FourHours("4hour", 4 * 60 * 60),
  Day("day", 24 * 60 * 60),
  Week("week", 7 * 24 * 60 * 60),
  Month("month", 30 * 24 * 60 * 60);

  private final String kiteKey;
  private final int offset;
}
