package com.dtech.kitecon.controller;


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

  @GetMapping("/fetch/{instrument}")
  public void fetchData(@PathVariable String instrument) {
    List<String> intervals = Arrays.asList("day", "15minute", "5minute", "1minute");
    String[] exchanges = new String[]{"NSE", "NFO"};
    intervals
        .forEach(interval -> dataFetchService.downloadCandleData(instrument, interval, exchanges));
  }

  @GetMapping("/fetch-interval/{instrument}/{interval}")
  public void fetchDataInterval(@PathVariable String instrument, @PathVariable String interval) {
    String[] exchanges = new String[]{"NSE", "NFO"};
    dataFetchService.downloadCandleData(instrument, interval, exchanges);
  }

  @GetMapping("/update-interval/{instrument}/{interval}")
  public void updateCandleDataToLatest(@PathVariable String instrument, @PathVariable String interval) {
    String[] exchanges = new String[]{"NSE", "NFO"};
    dataFetchService.updateInstrumentToLatest(instrument, interval, exchanges);
  }

    @GetMapping("/fetch/instruments/all")
  public void fetchAllInstruments() throws IOException, KiteException {
    dataFetchService.downloadAllInstruments();
  }
}
