package com.dtech.kitecon.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class DateRangeTest {

  @Test
  public void testDateRangeSplit() {
    ZonedDateTime endDate = ZonedDateTime.now();
    ZonedDateTime startDate = endDate.minusYears(1);
    DateRange dateRange = DateRange.builder()
        .endDate(endDate)
        .startDate(startDate)
        .build();
    List<DateRange> splits = dateRange.split(30);
    assertEquals(splits.size(), 13);
  }

}