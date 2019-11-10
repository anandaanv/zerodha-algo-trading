package com.dtech.kitecon.strategy.backtest;

import lombok.Value;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.num.Num;

import java.util.Map;

@Value
public class BacktestResult {

    private Map<String, Num> aggregatesResults;
    private TradingRecord tradingRecord;

}
