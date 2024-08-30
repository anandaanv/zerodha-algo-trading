package com.dtech.ta.patterns;

import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.series.SeriesType;
import com.dtech.chart.TriangleVisualizer;
import com.dtech.kitecon.data.Instrument;
import org.junit.jupiter.api.Test;
import org.ta4j.core.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class TriangleVisualizationTest {

    public static IntervalBarSeries createBarSeriesFromCSV(String csvFile) {
        BarSeries refSeries = new BaseBarSeries();
        IntervalBarSeries series = new ExtendedBarSeries(refSeries, Interval.OneHour, SeriesType.EQUITY, "rossari");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] values = line.split(",");
                LocalDateTime dateTime = LocalDateTime.parse(values[0], formatter);
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

    @Test
    public void testTriangle() {
        // Create a BarSeries and populate it with your data
        BarSeries series = createBarSeriesFromCSV("test_data/rossari_60.csv");
        // Populate series with your data...

        // Detect triangles
        TriangleDetector detector = new TriangleDetector(series);
        List<TrianglePattern> triangles = detector.detectTriangles(14);

        // Visualize triangles
        TriangleVisualizer visualizer = new TriangleVisualizer(series);
        visualizer.visualizeTriangles(triangles);

        triangles.forEach(System.out::println);
    }
}
