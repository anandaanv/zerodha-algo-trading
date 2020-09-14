package com.dtech.kitecon.strategy.backtest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.ta4j.core.Order.OrderType;
import org.ta4j.core.num.Num;

@Data
@AllArgsConstructor
@Builder
public class OrderRecord {

  /**
   * Type of the order
   */
  private OrderType type;

  /**
   * The index the order was executed
   */
  private int index;

  /**
   * The pricePerAsset for the order
   */
  private Double pricePerAsset;

  /**
   * The net price for the order, net transaction costs
   */
  private Double netPrice;

  /**
   * The amount to be (or that was) ordered
   */
  private Double amount;

  /**
   * Cost of executing the order
   */
  private Double cost;

}
