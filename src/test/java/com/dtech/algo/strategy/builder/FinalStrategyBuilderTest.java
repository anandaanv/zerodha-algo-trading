package com.dtech.algo.strategy.builder;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.strategy.TradeStrategy;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.IndicatorConfig;
import com.dtech.algo.strategy.config.StrategyConfig;
import com.dtech.algo.strategy.helper.ComponentHelper;
import com.dtech.algo.strategy.units.CachedIndicatorBuilder;
import com.dtech.algo.strategy.units.CachedRuleBuilder;
import com.dtech.kitecon.KiteconApplication;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.backtest.BarSeriesManager;
import org.ta4j.core.criteria.pnl.ProfitLossCriterion;
import org.ta4j.core.num.Num;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest(classes = {KiteconApplication.class})
class FinalStrategyBuilderTest {

  private List<IndicatorConfig> indicatorConfigs = new ArrayList<>();

  @MockBean
  private BarSeriesLoader barSeriesLoader;

  @Autowired
  private BarSeriesCache barSeriesCache;

  @Autowired
  private CachedIndicatorBuilder cachedIndicatorBuilder;

  @Autowired
  private CachedRuleBuilder cachedRuleBuilder;

  @Autowired
  private StrategyBuilderIfc strategyBuilderIfc;

  @Autowired
  private ComponentHelper componentHelper;

  @Test
  void buildStrategy() throws StrategyException {
    //Constants
    StrategyConfig config = componentHelper.buildSimpleSmaStrategy();

    TradeStrategy tradeStrategy = strategyBuilderIfc.buildStrategy(config);
    BarSeriesManager seriesManager = new BarSeriesManager(componentHelper.getBarSeries());
    TradingRecord tradingRecord = seriesManager.run(tradeStrategy);

    Num profit = new ProfitLossCriterion().calculate(componentHelper.getBarSeries(), tradingRecord);
    Assertions.assertEquals(tradingRecord.getPositionCount(), 5);
    Assertions.assertEquals(profit.doubleValue(), 281.73, 0.001);

    System.out.println("Number of trades for the strategy: " + tradingRecord.getPositionCount());
    // Analysis
    System.out.println(
            "Total profit for the strategy: " + profit);
  }

}