package com.dtech.kitecon.strategy.exec;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.orders.OrderManager;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.strategy.TradeDirection;
import com.dtech.kitecon.strategy.TradingStrategy;
import com.dtech.kitecon.strategy.builder.StrategyBuilder;
import com.dtech.kitecon.strategy.dataloader.InstrumentDataLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Order.OrderType;

@RequiredArgsConstructor
@Component
public class ProductionHandler {

  private final InstrumentRepository instrumentRepository;
  private final InstrumentDataLoader instrumentDataLoader;
  private final OrderManager ordermanager;
  private final ProductionSeriesManager productionSeriesManager;

  String[] exchanges = new String[]{"NSE", "NFO"};

  private Map<String, ProductionStrategyRunner> runners = new HashMap<>();

  public String startStrategy(String instrumentName, StrategyBuilder strategyBuilder,
      String direction) {
    Instrument tradingIdentity = instrumentRepository
        .findByTradingsymbolAndExchangeIn(instrumentName, exchanges);
    Map<Instrument, BarSeries> barSeriesMap = instrumentDataLoader.loadHybridData(instrumentName);
    TradingStrategy strategy = strategyBuilder.build(tradingIdentity, barSeriesMap);
    BarSeries barSeries = barSeriesMap.get(tradingIdentity);
    if (direction.equals("Buy")) {
      TradeDirection buy = TradeDirection.Buy;
      OrderType orderType = OrderType.BUY;
      return startExecution(tradingIdentity, strategy, barSeries, buy, orderType);
    }
    if (direction.equals("Sell")) {
      TradeDirection buy = TradeDirection.Sell;
      OrderType orderType = OrderType.SELL;
      return startExecution(tradingIdentity, strategy, barSeries, buy, orderType);
    }
    throw new RuntimeException("invalid data provided for trade direction");
  }

  private String startExecution(Instrument tradingIdentity, TradingStrategy strategy,
      BarSeries barSeries, TradeDirection buy, OrderType orderType) {
    AlgoTradingRecord record = new ProductionTradingRecord(orderType, ordermanager,
        tradingIdentity);
    ProductionStrategyRunner runner = new ProductionStrategyRunner(barSeries, strategy, record,
        productionSeriesManager, buy);
    runner.startStrategy();
    String uuid = UUID.randomUUID().toString();
    runners.put(uuid, runner);
    return uuid;
  }


}
