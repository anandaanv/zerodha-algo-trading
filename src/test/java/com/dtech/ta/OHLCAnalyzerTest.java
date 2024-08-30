package com.dtech.ta;

import com.dtech.chart.ChartCreator;
import org.junit.jupiter.api.Test;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BarSeries;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class OHLCAnalyzerTest {

    private BarSeries createBarSeriesFromCSV(String csvFile) {
        BarSeries series = new BaseBarSeries();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
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
    public void testMaximaMinima() {
        // Path to the CSV file
        String csvFile = "test_data/processed_ohlc_data.csv";

        // Create a BarSeries from the CSV data
        BarSeries series = createBarSeriesFromCSV(csvFile);

        OHLCAnalyzer analysis = new OHLCAnalyzer();
        // Find local maxima and minima
        List<BarTuple> maxima = analysis.findSignificantLocalMaxima(series);
        List<BarTuple> minima = analysis.findSignificantLocalMinima(series);
        ChartCreator chart = new ChartCreator();
        chart.createChart(series, maxima, minima);
    }


//    public void testDynamicSMAAndLocalExtrema() {
//        // Path to the CSV file
//        String csvFile = "test_data/processed_ohlc_data.csv";
//
//        // Create a BarSeries from the CSV data
//        BarSeries series = createBarSeriesFromCSV(csvFile);
//
//        TrendAnalysis analysis = new TrendAnalysis(series);
//        // Find local maxima and minima
//        Map<String, List<BarTuple>> accurateLocalMaximaAndMinima = analysis.getAccurateLocalMaximaAndMinima(5, 0.5);
//        List<BarTuple> localMaxima = accurateLocalMaximaAndMinima.get("Maxima");
//        List<BarTuple> localMinima = accurateLocalMaximaAndMinima.get("Minima");
//
//        // Get current price
//        double currentPrice = series.getLastBar().getClosePrice().doubleValue();
//
////        // Find active trendlines
////        List<TrendLineCalculated> activeTrendlines =
////                OHLCAnalyzer.findTrendlines(localMaxima, localMinima, series, currentPrice)
////                        .stream().sorted(Comparator.comparing(TrendLineCalculated::getReliabilityScore).reversed())
////                        .collect(Collectors.toList());
//
//        // Output results
//        System.out.println("Local Maxima: " + localMaxima);
//        System.out.println("Local Minima: " + localMinima);
////        for (TrendLineCalculated trendline : activeTrendlines) {
////            System.out.println((trendline.isSupport() ? "Support" : "Resistance") + " Trendline: " + trendline);
////        }
//
////        List<TrendLineCalculated> trendlines = activeTrendlines.stream()
////                .filter(t -> t.getReliabilityScore() > 10).toList();
//        TrendlineVisualizer chart = new TrendlineVisualizer("My Chart", series);
//        chart.highlightExtremaOnChart(localMaxima, localMinima);
//        chart.pack();
//        UIUtils.centerFrameOnScreen(chart);
//        chart.setVisible(true);
//        chart.saveChartAsJPEG("MyChart");
//
//        // Assertions to ensure the test runs correctly
//        assertFalse(localMaxima.isEmpty(), "Local maxima should not be empty");
//        assertFalse(localMinima.isEmpty(), "Local minima should not be empty");
//        assertFalse(activeTrendlines.isEmpty(), "Active trendlines should not be empty");
//    }


}
