package com.dtech.kitecon.service;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@AllArgsConstructor
@Builder
@Data
public class DateRange {

  private Instant startDate;
  private Instant endDate;

    public List<DateRange> split(int days) {
        List<DateRange> result = new ArrayList<>();
        Instant startRef = startDate;
        Instant endRef = startDate.plus(days, ChronoUnit.DAYS);
        while (endRef.isBefore(endDate)) {
            result.add(DateRange.builder()
                    .endDate(endRef)
                    .startDate(startRef).build());
            startRef = endRef.plusSeconds(1);
            endRef = startRef.plus(days, ChronoUnit.DAYS);
        }
        if(endRef.isAfter(startRef) && endRef.isBefore(endDate)) {
            result.add(DateRange.builder()
                    .endDate(endRef)
                    .startDate(startRef).build());
        }
        return result;
    }
}
