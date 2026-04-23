package com.dtech.kitecon.market.orders;

import com.dtech.kitecon.data.Instrument;

public interface OrderManager {

  String placeOrder(Double price, int amount, Instrument instrument, String orderType, String product)
      throws OrderException;

  Integer getActualOrderStatus(String orderId) throws OrderException;

}
