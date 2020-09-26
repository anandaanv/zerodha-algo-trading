package com.dtech.kitecon.strategy.exec;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.fetch.DataFetchException;
import com.dtech.kitecon.market.fetch.ZerodhaDataFetch;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;

@Qualifier("hybridDataLoader")
@Component
public class HybridDataLoader extends BarsLoader {

  private final KiteConnectConfig connectConfig;
  private final ZerodhaDataFetch zerodhaDataFetch;

  @Autowired
  public HybridDataLoader(
      CandleRepository fifteenMinuteCandleRepository, KiteConnectConfig connectConfig,
      ZerodhaDataFetch zerodhaDataFetch) {
    super(fifteenMinuteCandleRepository);
    this.connectConfig = connectConfig;
    this.zerodhaDataFetch = zerodhaDataFetch;
  }

  public BarSeries loadInstrumentSeriesWithLiveData(Instrument instrument, ZonedDateTime startDate,
      String interval)
      throws DataFetchException {
    BarSeries barSeries = super.loadInstrumentSeries(instrument, startDate, interval);
    List<BaseCandle> todaysFeed = zerodhaDataFetch
        .fetchTodaysData(instrument, interval);
    if (barSeries.isEmpty()) {
      return barSeries;
    }
    ZonedDateTime endTime = barSeries.getLastBar().getEndTime();
    todaysFeed.stream()
        .filter(baseCandle -> baseCandle.getTimestamp().isAfter(endTime.toLocalDateTime()))
        .forEach(candle -> super.addBarToSeries(barSeries, candle));
    return barSeries;
  }
}
