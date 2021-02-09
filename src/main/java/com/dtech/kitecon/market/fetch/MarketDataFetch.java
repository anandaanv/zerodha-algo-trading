package com.dtech.kitecon.market.fetch;

import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.service.DateRange;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import java.io.IOException;
import java.util.List;

public interface MarketDataFetch {



  String getProfile() throws IOException, KiteException;

  void fetch(DateRange dateRange, String instrumentToken, String interval)
      throws DataFetchException;

  List<BaseCandle> fetchTodaysData(Instrument instrument, String interval)
          throws DataFetchException;
}
