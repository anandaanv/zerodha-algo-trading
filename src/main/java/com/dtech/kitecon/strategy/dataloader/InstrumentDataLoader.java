package com.dtech.kitecon.strategy.dataloader;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.fetch.DataFetchException;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.strategy.exec.HybridDataLoader;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;

@RequiredArgsConstructor
@Component
@Log4j2
public class InstrumentDataLoader {

  private final InstrumentRepository instrumentRepository;
  private final HybridDataLoader barsLoader;

  public Map<Instrument, BarSeries> loadData(String instrumentName) {
    String[] exchanges = new String[]{"NSE", "NFO"};
    List<Instrument> instruments = getRelaventInstruments(instrumentName, exchanges);
    return instruments.stream()
        .collect(Collectors.toMap(instrument ->
            instrument, instrument -> barsLoader.loadInstrumentSeries(instrument)));
  }

  public Map<Instrument, BarSeries> loadHybridData(String instrumentName) {
    String[] exchanges = new String[]{"NSE", "NFO"};
    ZonedDateTime startDate = ZonedDateTime.now().minus(30, ChronoUnit.DAYS);
    List<Instrument> instruments = getRelaventInstruments(instrumentName, exchanges);
    return instruments.stream()
        .collect(Collectors.toMap(instrument ->
            instrument, instrument -> {
          try {
            return barsLoader.loadInstrumentSeriesWithLiveData(instrument, startDate);
          } catch (DataFetchException e) {
            log.catching(e);
            return new BaseBarSeries();
          }
        }));
  }




  private List<Instrument> getRelaventInstruments(String instrument, String[] exchanges) {
    return instrumentRepository
        .findAllByTradingsymbolStartingWithAndExchangeIn(instrument, exchanges);
  }

}
