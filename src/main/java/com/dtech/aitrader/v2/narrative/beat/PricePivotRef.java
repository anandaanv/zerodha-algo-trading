package com.dtech.aitrader.v2.narrative.beat;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PricePivotRef {
  int bar;
  String date;
  double price;
  String kind;
}
