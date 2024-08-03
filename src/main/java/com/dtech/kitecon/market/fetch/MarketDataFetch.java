package com.dtech.kitecon.market.fetch;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.service.DateRange;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import java.io.IOException;
import java.util.List;

public interface MarketDataFetch {



  String getProfile() throws IOException, KiteException;

  void fetch(DateRange dateRange, String instrumentToken, Interval interval)
      throws DataFetchException;

  List<Candle> fetchTodaysData(Instrument instrument, Interval interval)
          throws DataFetchException;
}
