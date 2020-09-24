package com.dtech.kitecon.strategy.exec;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.num.PrecisionNum;

@Log4j2
@Service
public class ProductionSeriesManager {

  public TradingRecord run(BarSeries barSeries, Strategy strategy,
      TradingRecord tradingRecord, Integer quantity) {
    int index = barSeries.getEndIndex();
    log.trace("Running strategy (indexes: {} -> {}): {} (starting with {})",
        index, index, strategy,
        tradingRecord.getCurrentTrade());

    boolean shouldOperate = strategy.shouldOperate(index, tradingRecord);
    if (shouldOperate) {
      tradingRecord.operate(index, barSeries.getBar(index).getClosePrice(),
          PrecisionNum.valueOf(quantity));
    }
    return tradingRecord;
  }

}
