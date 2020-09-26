package com.dtech.kitecon.controller;

import com.dtech.kitecon.service.StrategyService;
import com.dtech.kitecon.strategy.backtest.BacktestSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class StrategyController {

  private final StrategyService strategyService;

  @GetMapping("/test/{strategyName}/{instrument}")
  @ResponseBody
  public BacktestSummary backtestStrategy(@PathVariable String strategyName,
      @PathVariable String instrument) throws InterruptedException {
    return strategyService.testStrategy(instrument, strategyName, "15minute");
  }

}
