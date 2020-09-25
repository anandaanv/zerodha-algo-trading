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
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Order.OrderType;

@RequiredArgsConstructor
public class ProductionHandler {

  private final InstrumentRepository instrumentRepository;
  private final InstrumentDataLoader instrumentDataLoader;
  private final OrderManager ordermanager;
  private final ProductionSeriesManager productionSeriesManager;

  String[] exchanges = new String[]{"NSE", "NFO"};

  private AlgoTradingRecord record;

  private final Timer executionTimer = new Timer();

  public void initialise(String instrumentName, String direction) {
    Instrument tradingIdentity = instrumentRepository
        .findByTradingsymbolAndExchangeIn(instrumentName, exchanges);
    OrderType orderType = null;
    if (direction.equals("Buy")) {
      orderType = OrderType.BUY;
    }
    if (direction.equals("Sell")) {
      orderType = OrderType.SELL;
    }
    record = new ProductionTradingRecord(orderType, ordermanager,
        tradingIdentity);
  }

  public void runStrategy(String instrumentName, StrategyBuilder strategyBuilder,
      String direction) {
    Instrument tradingIdentity = instrumentRepository
        .findByTradingsymbolAndExchangeIn(instrumentName, exchanges);
    Map<Instrument, BarSeries> barSeriesMap = instrumentDataLoader.loadHybridData(tradingIdentity);
    TradingStrategy strategy = strategyBuilder.build(tradingIdentity, barSeriesMap);
    BarSeries barSeries = barSeriesMap.get(tradingIdentity);
    if (direction.equals("Buy")) {
      TradeDirection buy = TradeDirection.Buy;
      OrderType orderType = OrderType.BUY;
      startExecution(tradingIdentity, strategy, barSeries, buy, orderType);
    }
    if (direction.equals("Sell")) {
      TradeDirection buy = TradeDirection.Sell;
      OrderType orderType = OrderType.SELL;
      startExecution(tradingIdentity, strategy, barSeries, buy, orderType);
    }
  }

  private void startExecution(Instrument tradingIdentity, TradingStrategy strategy,
      BarSeries barSeries, TradeDirection buy, OrderType orderType) {
    ProductionStrategyRunner runner = new ProductionStrategyRunner(barSeries, strategy, record,
        productionSeriesManager, buy);
    runner.exec(barSeries, strategy);
  }


  public void startStrategy(String instrumentName, StrategyBuilder strategyBuilder,
      String direction) {
    executionTimer.schedule(getTimerTask(instrumentName, strategyBuilder, direction), 0, 60 * 1000);
  }

  private TimerTask getTimerTask(String instrumentName, StrategyBuilder strategyBuilder,
      String direction) {
    return new TimerTask() {
      @Override
      public void run() {
        runStrategy(instrumentName, strategyBuilder, direction);
      }
    };
  }

  public void stopStrategy() {
    executionTimer.cancel();
  }


}
