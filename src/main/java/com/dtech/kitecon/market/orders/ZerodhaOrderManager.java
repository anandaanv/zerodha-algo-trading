package com.dtech.kitecon.market.orders;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.Instrument;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Trade;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Component
public class ZerodhaOrderManager implements OrderManager {
  private final KiteConnectConfig kiteConnectConfig;

  @Override
  public String placeMISOrder(Double price, int amount, Instrument instrument, String orderType)
      throws OrderException {
    KiteConnect connect = kiteConnectConfig.getKiteConnect();
    OrderParams params = new OrderParams();
    params.exchange = "NSE";
    params.tradingsymbol = instrument.getTradingsymbol();
    params.transactionType = orderType.toUpperCase();
    params.quantity = amount;
    params.price = price;
    params.product = "MIS";
    params.orderType = "LIMIT";
    params.validity = "DAY";
    params.disclosedQuantity = amount;
    params.parentOrderId = UUID.randomUUID().toString();
    try {
      Order order = connect.placeOrder(params, "regular");
      return order.orderId;
    } catch (Throwable e) {
      throw new OrderException(e);
    }
  }

  @Override
  public Integer getActualOrderStatus(String orderId) throws OrderException {
    try {
      List<Trade> orderTrades = kiteConnectConfig.getKiteConnect().getOrderTrades(orderId);
      return orderTrades.stream()
          .collect(Collectors.summingInt(trade -> Integer.parseInt(trade.quantity)));
    } catch (Throwable th) {
      throw new OrderException(th);
    }
  }
}
