package com.dtech.kitecon.service;

import com.dtech.kitecon.data.Instrument;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class DataDownloadRequest {

  private Instrument instrument;
  private DateRange dateRange;
  private String interval;
}
