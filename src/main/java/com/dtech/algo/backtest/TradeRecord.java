package com.dtech.algo.backtest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Builder
@AllArgsConstructor
@Data
public class TradeRecord {

  /**
   * The type of the entry order
   */
  private Double profit;

  /**
   * The entry order
   */
  private OrderRecord entry;

  /**
   * The exit order
   */
  private OrderRecord exit;

}
