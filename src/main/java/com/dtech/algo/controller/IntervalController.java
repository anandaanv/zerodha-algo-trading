package com.dtech.algo.controller;

import com.dtech.algo.series.Interval;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/intervals")
public class IntervalController {

  /**
   * Returns mapping from UI interval keys to enum names, e.g.:
   * { "1m": "OneMinute", "5m": "FiveMinute", "15m": "FifteenMinute", "1h": "OneHour", "4h": "FourHours", "1d": "Day", "1w": "Week" }
   */
  @GetMapping("/mapping")
  public Map<String, String> getUiKeyMapping() {
    return Interval.uiKeyToEnumNameMap();
  }

  /**
   * Returns KLine Pro style period descriptors derived from the supported UI keys.
   * Example: [{ "multiplier":1, "timespan":"minute", "text":"1m" }, ...]
   */
  @GetMapping("/periods")
  public List<PeriodDef> getPeriods() {
    Map<String, String> map = Interval.uiKeyToEnumNameMap();
    List<String> keys = new ArrayList<>(map.keySet());

    // Sort keys sensibly: minutes -> hours -> day -> week with numeric ordering
    keys.sort(Comparator.comparingInt(IntervalController::orderKey));

    List<PeriodDef> result = new ArrayList<>();
    for (String key : keys) {
      PeriodDef def = toPeriodDef(key);
      if (def != null) {
        result.add(def);
      }
    }
    return result;
  }

  private static int orderKey(String key) {
    Parsed p = parseKey(key);
    int group = switch (p.unit) {
      case "m" -> 0;
      case "h" -> 1;
      case "d" -> 2;
      case "w" -> 3;
      default -> 99;
    };
    return group * 1000 + p.multiplier;
  }

  private static PeriodDef toPeriodDef(String key) {
    Parsed p = parseKey(key);
    if (p.multiplier <= 0 || p.unit == null) return null;
    String timespan = switch (p.unit) {
      case "m" -> "minute";
      case "h" -> "hour";
      case "d" -> "day";
      case "w" -> "week";
      default -> null;
    };
    if (timespan == null) return null;
    return new PeriodDef(p.multiplier, timespan, key);
  }

  private static final Pattern PERIOD_RE = Pattern.compile("^(\\d+)([a-zA-Z]+)$");

  private static Parsed parseKey(String key) {
    Matcher m = PERIOD_RE.matcher(key);
    if (m.matches()) {
      int mult = Integer.parseInt(m.group(1));
      String unit = m.group(2).toLowerCase(Locale.ROOT);
      return new Parsed(mult, unit);
    }
    return new Parsed(0, null);
  }

  private record Parsed(int multiplier, String unit) {}

  public static class PeriodDef {
    public int multiplier;
    public String timespan;
    public String text;

    public PeriodDef(int multiplier, String timespan, String text) {
      this.multiplier = multiplier;
      this.timespan = timespan;
      this.text = text;
    }
  }
}
