package com.dtech.aitrader.v2.narrative.beat;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PivotPairEntry {
  int bar;
  String date;
  double price;
  double macd;
}
