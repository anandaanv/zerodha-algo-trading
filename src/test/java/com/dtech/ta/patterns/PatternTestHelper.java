package com.dtech.ta.patterns;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class PatternTestHelper {
    public static BarSeries createBarSeriesFromCSV(String csvFile) {
        BarSeries series = new BaseBarSeries();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
//        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] values = line.split(",");
                LocalDate date = LocalDate.parse(values[0], formatter);
                LocalDateTime dateTime = date.atStartOfDay();
//                LocalDateTime dateTime = LocalDateTime.parse(values[0], formatter);
                double open = Double.parseDouble(values[1]);
                double high = Double.parseDouble(values[2]);
                double low = Double.parseDouble(values[3]);
                double close = Double.parseDouble(values[4]);
                Bar bar = new BaseBar(Duration.ofDays(1), dateTime.atZone(ZoneOffset.UTC), BigDecimal.valueOf(open),
                        BigDecimal.valueOf(high), BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.valueOf(0));
                series.addBar(bar);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return series;
    }
}
