package com.dtech.kitecon.controller;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.service.DataFetchService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class DataFetchController {

  private final DataFetchService dataFetchService;

  @GetMapping("/profile")
  public String getProfile() throws IOException, KiteException {
    return dataFetchService.getProfile();
  }

  @GetMapping("/fetch/{instrument}/{exchanges}")
  public void fetchData(@PathVariable String instrument, @PathVariable(required = false) String[] exchanges) {
    final String[] exchangeList = (exchanges == null || exchanges.length == 0) ? new String[]{"NSE", "NFO"} : exchanges;
    List<Interval> intervals = Arrays.stream(Interval.values()).toList();
    intervals.forEach(interval -> dataFetchService.downloadCandleData(instrument, interval, exchangeList));
  }

  @GetMapping("/fetch-interval/{instrument}/{interval}/{exchanges}")
  public void fetchDataInterval(@PathVariable String instrument, @PathVariable Interval interval, @PathVariable(required = false) String[] exchanges) {
    final String[] exchangeList = (exchanges == null || exchanges.length == 0) ? new String[]{"NSE", "NFO"} : exchanges;
    dataFetchService.downloadCandleData(instrument, interval, exchangeList);
  }

  @GetMapping("/update-interval/{instrument}/{interval}/{exchanges}")
  public void updateCandleDataToLatest(@PathVariable String instrument, @PathVariable Interval interval, @PathVariable(required = false) String[] exchanges) {
    final String[] exchangeList = (exchanges == null || exchanges.length == 0) ? new String[]{"NSE", "NFO"} : exchanges;
    dataFetchService.updateInstrumentToLatest(instrument, interval, exchangeList);
  }

  @GetMapping("/fetch/instruments/all")
  public void fetchAllInstruments() throws IOException, KiteException {
    dataFetchService.downloadAllInstruments();
  }
}
