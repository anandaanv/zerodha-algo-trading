package com.dtech.kitecon.service;

import com.dtech.kitecon.strategy.backtest.BackTestingHandler;
import com.dtech.kitecon.strategy.backtest.BacktestResult;
import com.dtech.kitecon.strategy.backtest.BacktestSummary;
import com.dtech.kitecon.strategy.builder.StrategyBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StrategyService {

    private Map<String, StrategyBuilder> strategyBuilderMap;

    private final Set<StrategyBuilder> strategyBuilders;

    private final BackTestingHandler backTestingHandler;

    @PostConstruct
    public void buildStrategyBuilderMap() {
        strategyBuilderMap = strategyBuilders.stream().collect(Collectors.toMap(s -> s.getName(), s -> s));
    }

    public BacktestSummary testStrategy(String instrument, String strategyName) {
        return backTestingHandler.execute(instrument, strategyBuilderMap.get(strategyName));
    }

}
