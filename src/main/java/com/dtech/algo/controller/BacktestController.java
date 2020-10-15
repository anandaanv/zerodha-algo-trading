package com.dtech.algo.controller;

import com.dtech.algo.backtest.BackTestingHandlerJson;
import com.dtech.algo.backtest.BacktestInput;
import com.dtech.algo.backtest.BacktestResult;
import com.dtech.algo.exception.StrategyException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class BacktestController {

    private final BackTestingHandlerJson backTestingHandlerJson;

    @PostMapping("/backtest")
    public BacktestResult runBacktest(@RequestBody BacktestInput backtestInput) throws StrategyException {
        return backTestingHandlerJson.execute(backtestInput);
    }

}
