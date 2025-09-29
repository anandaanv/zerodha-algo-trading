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

        while (startRef.isBefore(endDate)) {
            Instant endRef = startRef.plus(days, ChronoUnit.DAYS);

            // If endRef goes beyond the endDate, use endDate as the final point
            if (endRef.isAfter(endDate)) {
                endRef = endDate;
            }

            result.add(DateRange.builder()
                    .startDate(startRef)
                    .endDate(endRef)
                    .build());

            startRef = endRef.plusSeconds(1);
        }
    
        return result;
    }
}
