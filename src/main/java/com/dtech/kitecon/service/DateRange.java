package com.dtech.kitecon.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@AllArgsConstructor
@Builder
@Data
public class DateRange {

  private ZonedDateTime startDate;
  private ZonedDateTime endDate;

  public List<DateRange> split(int days) {
    List<DateRange> result = new ArrayList<>();
    ZonedDateTime startRef = startDate;
    ZonedDateTime endRef = startDate.plusDays(days);
    while (endRef.isBefore(endDate)) {
      result.add(DateRange.builder()
          .endDate(endRef)
          .startDate(startRef).build());
      startRef = endRef;
      endRef = startRef.plusDays(days);
    }
    result.add(DateRange.builder()
        .endDate(endDate)
        .startDate(startRef).build());
    return result;
  }
}
