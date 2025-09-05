package com.dtech.chartpattern.zigzag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZigZagPoint {
    public enum Type { HIGH, LOW }

    private Type type;
    private LocalDateTime timestamp;
    private int barIndex;
    private long sequence;        // stable sequence based on timestamp ordering (epoch seconds)
    private double value;         // price at pivot (high for HIGH, low for LOW)
    private double atrAtPivot;    // optional metadata

    private Double retracementPct;
    private Double extensionPct;

    private void setSequence(long sequence) {
        this.sequence = sequence;
        this.barIndex = Long.valueOf((sequence - LocalDateTime.of(LocalDate.ofYearDay(2000,1), LocalTime.of(0,0))
                .toEpochSecond(ZoneOffset.UTC)) / 60).intValue();
    }

    public boolean isHigh() { return type == Type.HIGH; }
    public boolean isLow() { return type == Type.LOW; }
}
