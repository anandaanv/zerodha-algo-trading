package com.dtech.kitecon.market.orders;

import com.dtech.kitecon.data.Instrument;

public interface OrderManager {

  String placeMISOrder(Double price, int amount, Instrument instrument, String orderType)
      throws OrderException;

  Integer getActualOrderStatus(String orderId) throws OrderException;

}
