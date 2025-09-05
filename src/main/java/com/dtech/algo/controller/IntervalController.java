package com.dtech.algo.controller;

import com.dtech.algo.series.Interval;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
}
