package com.dtech.algo.backtest;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.strategy.builder.StrategyBuilderIfc;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.algo.strategy.config.StrategyConfig;
import com.dtech.algo.strategy.helper.ComponentHelper;
import com.dtech.algo.strategy.units.CachedIndicatorBuilder;
import com.dtech.kitecon.KiteconApplication;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Spy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Collections;
import java.util.List;

@SpringBootTest(classes = {KiteconApplication.class})
class BackTestingHandlerJsonTest {

    @MockBean
    private BarSeriesLoader barSeriesLoader;

    @Spy
    @Autowired
    private StrategyBuilderIfc strategyBuilderIfc;

    @Autowired
    private CachedIndicatorBuilder cachedIndicatorBuilder;

    @Autowired
    private BarSeriesCache barSeriesCache;

    @Autowired
    private ConstantsCache constantsCache;

    @Autowired
    private ComponentHelper componentHelper;

    @Autowired
    private BackTestingHandlerJson backTestingHandlerJson;

    @Test
    void execute() throws StrategyException {
        String sbin = "sbin";
        BarSeriesConfig barSeriesConfig = BarSeriesConfig.builder()
                .name(sbin).build();
        componentHelper.setupBarSeries(sbin);
        StrategyConfig strategyConfig = componentHelper.buildSimpleSmaStrategy();
        List<BarSeriesConfig> barSeriesConfigs = Collections.singletonList(barSeriesConfig);
        strategyConfig.setBarSeriesConfigs(barSeriesConfigs);
        strategyConfig.setBarSeriesToTrade(sbin);

        BacktestInput backtestInput = BacktestInput.builder()
                .barSeriesConfigs(barSeriesConfigs)
                .barSeriesName(sbin)
                .strategyConfig(strategyConfig)
                .build();

        BacktestResult backtestResult = backTestingHandlerJson.execute(backtestInput);
        double totalProfit = backtestResult.getAggregatesResults().get("TotalProfit");
        Assertions.assertEquals(totalProfit, 1.329, 0.001);

    }
}