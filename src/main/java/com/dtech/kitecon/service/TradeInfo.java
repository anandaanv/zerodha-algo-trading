package com.dtech.kitecon.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class TradeInfo {

  private String symbol;
  private String direction;

}
