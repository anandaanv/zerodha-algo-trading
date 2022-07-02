package com.dtech.algo.backtest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.ta4j.core.Trade.TradeType;
import org.ta4j.core.Trade;

import java.time.ZonedDateTime;

@Data
@AllArgsConstructor
@Builder
public class OrderRecord {

  /**
   * Type of the order
   */
  private TradeType type;

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

  private ZonedDateTime dateTime;

}
