package com.dtech.kitecon.strategy.exec;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.orders.OrderException;
import com.dtech.kitecon.market.orders.OrderManager;
import lombok.extern.log4j.Log4j2;
import org.ta4j.core.BaseTradingRecord;
import org.ta4j.core.Trade;
import org.ta4j.core.num.Num;

@Log4j2
public class ProductionTradingRecord extends BaseTradingRecord implements AlgoTradingRecord {

  private final OrderManager ordermanager;
  private final Instrument instrument;
  private int actualQuantity = 0;
  private String orderId = null;
  private Trade.TradeType orderType = null;

  public ProductionTradingRecord(Trade.TradeType orderType,
                                 OrderManager ordermanager, Instrument instrument) {
    super(orderType);
    this.ordermanager = ordermanager;
    this.instrument = instrument;
    this.orderType = orderType;
  }

  public void operate(int index, Num price, Num amount) {
    super.operate(index, price, amount);
    Trade.TradeType type = getOrderType();
    try {
      this.orderId = ordermanager.placeMISOrder(price.doubleValue(),
          amount.intValue(), instrument, type.name());
    } catch (OrderException e) {
      log.catching(e);
    }
  }

  private Trade.TradeType getOrderType() {
    if (getCurrentPosition().isOpened()) {
      return orderType;
    } else {
      return orderType.complementType();
    }
  }

  @Override
  public void updateOrderStatus() throws OrderException {
    this.actualQuantity = ordermanager.getActualOrderStatus(orderId);
  }

}
