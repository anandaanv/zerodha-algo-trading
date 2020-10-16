package com.dtech.algo.controller;

import com.dtech.algo.backtest.BackTestingHandlerJson;
import com.dtech.algo.backtest.BacktestInput;
import com.dtech.algo.backtest.BacktestResult;
import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.indicators.IndicatorRegistry;
import com.dtech.algo.rules.RuleRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class MetadataController {

    private final IndicatorRegistry indicatorRegistry;
    private final RuleRegistry ruleRegistry;

    @PostMapping("/meta/rule-detail")
    public BacktestResult getRuleDetails(@RequestBody BacktestInput backtestInput) throws StrategyException {
        return null;
    }

}
