package com.dtech.kitecon.strategy.backtest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.ta4j.core.Order;
import org.ta4j.core.Order.OrderType;
import org.ta4j.core.num.Num;

@Builder
@AllArgsConstructor
@Data
public class TradeRecord {
  /** The type of the entry order */
  private Double profit;

  /** The entry order */
  private OrderRecord entry;

  /** The exit order */
  private OrderRecord exit;

}
