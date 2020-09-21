package com.dtech.kitecon.strategy.exec;

import com.dtech.kitecon.market.orders.OrderException;
import org.ta4j.core.TradingRecord;

public interface AlgoTradingRecord extends TradingRecord {
  void updateOrderStatus() throws OrderException;
}
