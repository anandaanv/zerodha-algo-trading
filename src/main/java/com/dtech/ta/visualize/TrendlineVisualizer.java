package com.dtech.ta.visualize;

import com.dtech.ta.BarTuple;
import com.dtech.ta.TrendLineCalculated;
import com.dtech.ta.divergences.Divergence;
import com.dtech.ta.divergences.DivergenceDirection;
import com.dtech.ta.elliott.Wave;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.DefaultHighLowDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.DoubleSummaryStatistics;
import java.util.List;

import static com.dtech.ta.visualize.RSIVisualizer.createRSIPlot;
import static com.dtech.ta.visualize.StochasticVisualizer.createStochasticPlot;

public class TrendlineVisualizer {

    private final JFreeChart chart;

    public TrendlineVisualizer(String title, BarSeries series, List<TrendLineCalculated> trendlines, List<BarTuple> maxima, List<Divergence> divergences) {
        this(title, series, trendlines, maxima, divergences, true, Collections.emptyList());
    }

    public TrendlineVisualizer(String title, BarSeries series, List<TrendLineCalculated> trendlines, List<BarTuple> maxima,
                               List<Divergence> divergences, boolean extendToCurrent, List<Wave> waves) {
        // Create a combined plot for candlesticks and indicators
        CombinedDomainXYPlot combinedPlot = new CombinedDomainXYPlot(new DateAxis("Time"));

        // Create the candlestick chart and add it to the combined plot
        XYPlot candlestickPlot = createCandlestickPlot(series, trendlines, maxima, divergences, extendToCurrent);
        combinedPlot.add(candlestickPlot, 5);

        // Create and add RSI plot
        XYPlot rsiPlot = createRSIPlot(series, divergences);
        combinedPlot.add(rsiPlot, 1);

        // Create and add MACD plot
        XYPlot macdPlot = MACDVisualizer.createMACDPlot(series, divergences);
        combinedPlot.add(macdPlot, 1);

        // Create and add Stochastic plot
        XYPlot stochasticPlot = createStochasticPlot(series, divergences);
        combinedPlot.add(stochasticPlot, 1);

        highlightWaves(candlestickPlot, waves, series);

        // Create the final chart with the combined plot
        chart = new JFreeChart(title, JFreeChart.DEFAULT_TITLE_FONT, combinedPlot, false);
    }

    private XYPlot createCandlestickPlot(BarSeries series, List<TrendLineCalculated> trendlines, List<BarTuple> maxima,
                                         List<Divergence> divergences, boolean extendToCurrent) {
        XYPlot plot = new XYPlot();

        // Create and set the Y-axis (NumberAxis) for the plot
        NumberAxis rangeAxis = new NumberAxis("Price");
        plot.setRangeAxis(rangeAxis);  // Ensure the Y-axis is initialized

        // Plot the main candlestick chart
        plot.setDataset(createCandlestickDataset(series));
        plot.setRenderer(new CandlestickRenderer());

        // Set the Y-axis range dynamically
        adjustYAxisRange(plot, series);

        // Add trendlines to the plot
        //addTrendlinesToPlot(plot, trendlines, series, extendToCurrent);

        // Highlight local maxima and minima
        highlightLocalMaxMin(plot, maxima, series);

        // Plot divergences on the main candlestick chart
        plotDivergencesOnCandlestickChart(plot, divergences, series);

        return plot;
    }


    private void adjustYAxisRange(XYPlot plot, BarSeries series) {
        // Calculate the lowest and highest values from the candlestick series
        DoubleSummaryStatistics statistics = series.getBarData().stream()
                .mapToDouble(bar -> bar.getLowPrice().doubleValue())
                .summaryStatistics();

        double minValue = statistics.getMin();
        double maxValue = statistics.getMax();

        // Adjust the Y-axis range to fit the candlestick data
        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setRange(minValue, maxValue * 1.05);  // Add a small buffer to the max value for clarity
    }

    // Method to plot divergences on the main candlestick chart
    private void plotDivergencesOnCandlestickChart(XYPlot plot, List<Divergence> divergences, BarSeries series) {
        XYSeriesCollection divergenceDataset = new XYSeriesCollection();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(false, true);

        XYSeries bullishSeries = new XYSeries("Bullish Divergence");
        XYSeries bearishSeries = new XYSeries("Bearish Divergence");

        for (Divergence divergence : divergences) {
            for (BarTuple candle : divergence.getCandles()) {
                double x = series.getBar(candle.getIndex()).getEndTime().toInstant().toEpochMilli();
                double y = candle.getValue();
                if (divergence.getDirection() == DivergenceDirection.Bullish) {
                    bullishSeries.add(x, y);
                } else {
                    bearishSeries.add(x, y);
                }
            }
        }

        divergenceDataset.addSeries(bullishSeries);
        divergenceDataset.addSeries(bearishSeries);
        plot.setDataset(2, divergenceDataset);

        // Customize renderer
        renderer.setSeriesPaint(0, Color.GREEN); // Bullish divergence in green
        renderer.setSeriesPaint(1, Color.RED);   // Bearish divergence in red
        plot.setRenderer(2, renderer);
    }


    private DefaultHighLowDataset createCandlestickDataset(BarSeries series) {
        int itemCount = series.getBarCount();
        Date[] dates = new Date[itemCount];
        double[] highs = new double[itemCount];
        double[] lows = new double[itemCount];
        double[] opens = new double[itemCount];
        double[] closes = new double[itemCount];
        double[] volumes = new double[itemCount];

        for (int i = 0; i < itemCount; i++) {
            Bar bar = series.getBar(i);
            dates[i] = Date.from(bar.getEndTime().toInstant());
            highs[i] = bar.getHighPrice().doubleValue();
            lows[i] = bar.getLowPrice().doubleValue();
            opens[i] = bar.getOpenPrice().doubleValue();
            closes[i] = bar.getClosePrice().doubleValue();
            volumes[i] = bar.getVolume().doubleValue();
        }

        return new DefaultHighLowDataset("OHLC Data", dates, highs, lows, opens, closes, volumes);
    }

    private void addTrendlinesToPlot(XYPlot plot, List<TrendLineCalculated> trendlines, BarSeries series, boolean extendtoCurrent) {
        XYSeriesCollection trendlineDataset = new XYSeriesCollection();
        int counter = 0;

        long lastTime = series.getBar(series.getEndIndex()).getEndTime().toInstant().toEpochMilli();
        int lastIndex = series.getEndIndex();
        double currentPrice = series.getBar(lastIndex).getClosePrice().doubleValue();

        for (TrendLineCalculated trendline : trendlines) {
            XYSeries trendlineSeries = new XYSeries(trendline.isSupport() ? "Support Trendline " + ++counter : "Resistance Trendline " + ++counter);

            // Add the original points of the trendline
            for (BarTuple point : trendline.getPoints()) {
                double x = series.getBar(point.getIndex()).getEndTime().toInstant().toEpochMilli();
                double y = point.getValue(); // Use the correct OHLC value from BarTuple
                trendlineSeries.add(x, y);
            }

            if(extendtoCurrent) {
                // Extend the trendline to the latest point
                double extendedY = trendline.calculateYValue(lastIndex);
                if (Math.abs(extendedY - currentPrice) <= 0.2 * currentPrice) {
                    trendlineSeries.add(lastTime, extendedY);
                }
            }

            trendlineDataset.addSeries(trendlineSeries);
        }

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setSeriesPaint(0, Color.RED);
        renderer.setSeriesStroke(0, new BasicStroke(2.0f));

        plot.setDataset(1, trendlineDataset);
        plot.setRenderer(1, renderer);
    }

    private void highlightLocalMaxMin(XYPlot plot, List<BarTuple> maxima, BarSeries series) {
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(false, true); // Shapes only, no lines

        // Customize shape renderer for maxima and minima
        renderer.setSeriesPaint(0, Color.RED); // Maxima - red dots
        renderer.setSeriesShape(0, new Ellipse2D.Double(-3, -3, 6, 6)); // Red dot for maxima

        XYSeriesCollection maxMinDataset = new XYSeriesCollection();
        XYSeries maximaSeries = new XYSeries("Maxima");

        // Plot maxima
        for (BarTuple max : maxima) {
            double x = series.getBar(max.getIndex()).getEndTime().toInstant().toEpochMilli();
            double y = max.getValue();
            maximaSeries.add(x, y);
        }

        maxMinDataset.addSeries(maximaSeries);
        plot.setDataset(2, maxMinDataset); // Add maxima and minima dataset
        plot.setRenderer(2, renderer);     // Add custom renderer for maxima
    }

    public void saveChartAsJPEG(String filename) {
        // Define the file path and ensure the directory exists
        String directory = "tlbo/";
        saveChartAsJpg(filename, directory);
    }

    public void saveChartAsJpg(String filename, String directory) {
        File imageFile = new File(directory + filename + ".jpg");
        imageFile.getParentFile().mkdirs(); // Ensure the directory exists

        try {
            // Save the chart as a JPEG file with the specified width and height
            ChartUtils.saveChartAsJPEG(imageFile, chart, 2000, 1000);
            System.out.println("Chart saved to " + imageFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error saving chart to JPEG: " + e.getMessage());
        }
    }

    public void highlightWaves(XYPlot plot, List<Wave> waves, BarSeries series) {
        XYSeriesCollection waveDataset = new XYSeriesCollection();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);

        for (int i = 0; i < waves.size(); i++) {
            Wave wave = waves.get(i);
            XYSeries waveSeries = new XYSeries("Wave " + wave.getWaveNumber() + "+" + wave.getStart().getIndex());

            // Add start and end points of the wave
            double startX = series.getBar(wave.getStart().getIndex()).getEndTime().toInstant().toEpochMilli();
            double startY = wave.getStart().getValue();
            double endX = series.getBar(wave.getEnd().getIndex()).getEndTime().toInstant().toEpochMilli();
            double endY = wave.getEnd().getValue();

            waveSeries.add(startX, startY);
            waveSeries.add(endX, endY);

            waveDataset.addSeries(waveSeries);

            // Configure rendering: boldness and color depending on the wave number
            float strokeWidth = 2.0f + (i * 0.5f);  // Increase stroke width slightly for each wave
            Color waveColor = getColorForWave(i);

            renderer.setSeriesStroke(i, new BasicStroke(strokeWidth));
            renderer.setSeriesPaint(i, waveColor);
        }

        plot.setDataset(3, waveDataset);  // Add wave dataset to a new slot
        plot.setRenderer(3, renderer);
    }

    private Color getColorForWave(int waveIndex) {
        switch (waveIndex) {
            case 0: return Color.BLUE;  // Wave 1
            case 1: return Color.GREEN;  // Wave 2
            case 2: return Color.ORANGE; // Wave 3
            case 3: return Color.MAGENTA;// Wave 4
            case 4: return Color.RED;    // Wave 5
            default: return Color.BLACK; // Default for any additional waves
        }
    }




}
