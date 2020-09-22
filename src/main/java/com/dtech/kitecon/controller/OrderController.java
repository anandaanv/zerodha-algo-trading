package com.dtech.kitecon.controller;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.orders.OrderException;
import com.dtech.kitecon.market.orders.OrderManager;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OrderController {

  private String[] exchanges = new String[]{"NSE"};

  private final InstrumentRepository instrumentRepository;
  private final OrderManager orderManager;

  @GetMapping("/order/{instrument}/{direction}/{price}")
  public String placeOrder(@PathVariable Double price,
      @PathVariable String instrument, @PathVariable String direction) throws OrderException {
    Instrument tradingIdentity = instrumentRepository
        .findByTradingsymbolAndExchangeIn(instrument, exchanges);
    return orderManager.placeMISOrder(price, 1, tradingIdentity, direction);
  }

}
