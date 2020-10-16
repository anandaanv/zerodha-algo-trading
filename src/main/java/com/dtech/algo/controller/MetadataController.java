package com.dtech.algo.controller;

import com.dtech.algo.backtest.BackTestingHandlerJson;
import com.dtech.algo.backtest.BacktestInput;
import com.dtech.algo.backtest.BacktestResult;
import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.indicators.IndicatorInfo;
import com.dtech.algo.indicators.IndicatorRegistry;
import com.dtech.algo.rules.RuleInfo;
import com.dtech.algo.rules.RuleRegistry;
import com.dtech.algo.strategy.config.IndicatorConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class MetadataController {

    private final IndicatorRegistry indicatorRegistry;
    private final RuleRegistry ruleRegistry;

    @GetMapping("/meta/indicator-detail")
    public Map<String, IndicatorInfo> getIndicatorDetails() throws StrategyException {
        return indicatorRegistry.getAllObjectNames().stream()
                .collect(Collectors.toMap(name -> name, name -> indicatorRegistry.getObjectInfo(name)));
    }

    @GetMapping("/meta/rule-detail")
    public Map<String, RuleInfo> getRuleDetails() throws StrategyException {
        return ruleRegistry.getAllObjectNames().stream()
                .collect(Collectors.toMap(name -> name, name -> ruleRegistry.getObjectInfo(name)));
    }

}
