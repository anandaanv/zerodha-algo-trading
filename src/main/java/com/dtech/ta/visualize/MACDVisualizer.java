package com.dtech.ta.visualize;

import com.dtech.ta.divergences.Divergence;
import com.dtech.ta.divergences.IndicatorType;
import org.jfree.chart.annotations.XYLineAnnotation;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYBarDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.List;

import static com.dtech.ta.visualize.VisualizerHelper.plotDivergences;

public class MACDVisualizer {
    static XYPlot createMACDPlot(BarSeries series, List<Divergence> divergences) {
        XYPlot plot = new XYPlot();

        // Create MACD indicator
        MACDIndicator macdIndicator = new MACDIndicator(new ClosePriceIndicator(series), 12, 26);
        EMAIndicator signalLine = new EMAIndicator(macdIndicator, 9);

        // Create datasets for MACD line, Signal line, and Histogram
        XYSeries macdSeries = new XYSeries("MACD Line");
        XYSeries signalSeries = new XYSeries("Signal Line");
        XYSeries histogramSeries = new XYSeries("MACD Histogram");

        for (int i = 0; i < series.getBarCount(); i++) {
            long time = series.getBar(i).getEndTime().toInstant().toEpochMilli();
            double macdValue = macdIndicator.getValue(i).doubleValue();
            double signalValue = signalLine.getValue(i).doubleValue();
            double histogramValue = macdValue - signalValue;

            // Add values to the MACD, Signal, and Histogram datasets
            macdSeries.add(time, macdValue);
            signalSeries.add(time, signalValue);
            histogramSeries.add(time, histogramValue); // For the histogram
        }

        // Add the MACD and Signal line to a dataset
        XYSeriesCollection lineDataset = new XYSeriesCollection();
        lineDataset.addSeries(macdSeries);
        lineDataset.addSeries(signalSeries);

        // Add the histogram dataset
        XYSeriesCollection histogramDataset = new XYSeriesCollection(histogramSeries);

        // Create renderer for MACD and Signal lines
        XYLineAndShapeRenderer lineRenderer = new XYLineAndShapeRenderer(true, false);
        lineRenderer.setSeriesPaint(0, Color.BLUE); // MACD line
        lineRenderer.setSeriesPaint(1, Color.RED);  // Signal line

        // Create renderer for Histogram (bars)
        XYBarRenderer barRenderer = new XYBarRenderer();
        barRenderer.setSeriesPaint(0, Color.GRAY);  // Histogram

        // Set up the dataset and renderer in the plot
        plot.setDataset(0, lineDataset);
        plot.setRenderer(0, lineRenderer);

        plot.setDataset(1, new XYBarDataset(histogramDataset, 0.8)); // Set width for histogram bars
        plot.setRenderer(1, barRenderer);

        // Initialize the Y-axis (NumberAxis) for the MACD chart
        NumberAxis rangeAxis = new NumberAxis("MACD");
        plot.setRangeAxis(rangeAxis);

        // Initialize the domain axis (X-axis) for the time series
        DateAxis domainAxis = new DateAxis("Time");
        domainAxis.setDateFormatOverride(new SimpleDateFormat("yyyy-MM-dd"));
        plot.setDomainAxis(domainAxis);

        // Set the Y-axis range dynamically based on the MACD values
        adjustMACDYAxisRange(plot, macdIndicator);

        // Add a horizontal line at MACD = 0
        addMACDZeroLine(plot);

        // Plot divergences on MACD chart
        plotDivergences(plot, divergences, IndicatorType.MACD, series);

        return plot;
    }

    private static void adjustMACDYAxisRange(XYPlot plot, MACDIndicator macdIndicator) {
        // Find the minimum and maximum values in the MACD data
        double minValue = Double.MAX_VALUE;
        double maxValue = Double.MIN_VALUE;

        for (int i = 0; i < macdIndicator.getBarSeries().getBarCount(); i++) {
            double value = macdIndicator.getValue(i).doubleValue();
            if (value < minValue) {
                minValue = value;
            }
            if (value > maxValue) {
                maxValue = value;
            }
        }

        // Ensure that the range axis is properly initialized
        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();

        // Adjust the Y-axis range to fit the MACD data, with a small buffer
        rangeAxis.setRange(minValue * 1.05, maxValue * 1.05);  // Add buffer for clarity
    }

    private static void addMACDZeroLine(XYPlot plot) {
        // Create a horizontal line at Y = 0 across the entire X-axis range
        XYLineAnnotation zeroLine = new XYLineAnnotation(
                plot.getDomainAxis().getRange().getLowerBound(), 0,
                plot.getDomainAxis().getRange().getUpperBound(), 0,
                new BasicStroke(1.5f), Color.GRAY
        );

        // Add the annotation (line) to the plot
        plot.addAnnotation(zeroLine);
    }
}
