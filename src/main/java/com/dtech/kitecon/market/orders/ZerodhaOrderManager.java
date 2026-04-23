package com.dtech.kitecon.market.orders;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.facade.MarketException;
import com.dtech.kitecon.market.facade.MarketFacade;
import com.dtech.kitecon.market.facade.MarketFacadeProvider;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;
import com.zerodhatech.models.Trade;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Component
@Qualifier("zerodhaOrderManagerOld")
public class ZerodhaOrderManager implements OrderManager {
  private final MarketFacadeProvider marketFacadeProvider;

  @Override
  public String placeMISOrder(Double price, int amount, Instrument instrument, String orderType)
      throws OrderException {
    try {
      MarketFacade facade = marketFacadeProvider.getFacade();
      OrderParams params = new OrderParams();
      String exchange = instrument.getExchange() != null ? instrument.getExchange() : "NSE";
      params.exchange = exchange;
      params.tradingsymbol = instrument.getTradingsymbol();
      params.transactionType = orderType.toUpperCase();
      params.quantity = amount;
      params.price = price;
      params.product = "NRML";
      params.orderType = "LIMIT";
      params.validity = "DAY";
      OrderResponse response = facade.placeOrder(params, "regular");
      return response.orderId;
    } catch (MarketException e) {
      throw new OrderException(e);
    } catch (Throwable e) {
      throw new OrderException(e);
    }
  }

  @Override
  public Integer getActualOrderStatus(String orderId) throws OrderException {
    try {
      MarketFacade facade = marketFacadeProvider.getFacade();
      List<Trade> orderTrades = facade.getOrderTrades(orderId);
      return orderTrades.stream()
          .collect(Collectors.summingInt(trade -> Integer.parseInt(trade.quantity)));
    } catch (MarketException e) {
      throw new OrderException(e);
    } catch (Throwable th) {
      throw new OrderException(th);
    }
  }
}
