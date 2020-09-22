package com.dtech.kitecon.strategy.exec;

import com.dtech.kitecon.strategy.TradeDirection;
import com.dtech.kitecon.strategy.TradingStrategy;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;

@Log4j2
@RequiredArgsConstructor
public class ProductionStrategyRunner {

  private final BarSeries barSeries;
  private final TradingStrategy tradingStrategy;
  private final AlgoTradingRecord record;
  private final ProductionSeriesManager productionSeriesManager;
  private final TradeDirection tradeDirection;

  private AtomicBoolean isTradeActive = new AtomicBoolean(false);

  public void exec(BarSeries barSeries, TradingStrategy tradingStrategy) {
    Strategy strategy = getStrategy(tradingStrategy);
    log.info("Running strategy for" + this.record);
    productionSeriesManager.run(barSeries, strategy, this.record, 1);
  }

  private Strategy getStrategy(TradingStrategy tradingStrategy) {
    if (tradeDirection.isBuy()) {
      return tradingStrategy.getBuyStrategy();
    } else {
      return tradingStrategy.getSellStrategy();
    }
  }

}
