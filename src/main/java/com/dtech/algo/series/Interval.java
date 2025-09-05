package com.dtech.algo.series;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Getter
public enum Interval {
  OneMinute("minute", 60, "1m"),
  ThreeMinute("3minute", 3 * 60, "3m"),
  FiveMinute("5minute", 5 * 60, "5m"),
  FifteenMinute("15minute", 15 * 60, "15m"),
  ThirtyMinute("30minute", 30 * 60, "30m"),
  OneHour("60minute", 60 * 60, "1h"),
  FourHours("4hour", 4 * 60 * 60, "4h"),
  Day("day", 24 * 60 * 60, "1d"),
  Week("week", 7 * 24 * 60 * 60, "1w");
//  Month("month", 30 * 24 * 60 * 60);

  private final String kiteKey;
  private final int offset;
  private final String uiKey;

  public Interval getParent() {
    if (this == OneMinute || this == ThreeMinute || this == FiveMinute) {
      return FifteenMinute;
    } else if (this == FifteenMinute) {
      return OneHour;
    } else if (this == OneHour) {
      return Day;
    } else return Week;
  }

  public static Map<String, String> uiKeyToEnumNameMap() {
    return Arrays.stream(Interval.values())
        .collect(Collectors.toMap(Interval::getUiKey, Interval::name));
  }

  public static Interval fromUiKey(String key) {
    for (Interval i : values()) {
      if (i.uiKey.equalsIgnoreCase(key)) {
        return i;
      }
    }
    throw new IllegalArgumentException("Unknown interval uiKey: " + key);
  }
}
