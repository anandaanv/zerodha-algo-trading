package com.dtech.ta.visualize;

import com.dtech.ta.BarTuple;
import com.dtech.ta.divergences.Divergence;
import com.dtech.ta.divergences.DivergenceDirection;
import com.dtech.ta.divergences.IndicatorType;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.ta4j.core.BarSeries;

import java.awt.*;
import java.util.List;

public class VisualizerHelper {

    // Method to plot divergences on their respective subplots
    public static void plotDivergences(XYPlot plot, List<Divergence> divergences, IndicatorType indicatorType, BarSeries series) {
        XYSeriesCollection divergenceDataset = new XYSeriesCollection();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, true);  // Enable lines and shapes

        XYSeries bullishSeries = new XYSeries("Bullish Divergence");
        XYSeries bearishSeries = new XYSeries("Bearish Divergence");

        for (Divergence divergence : divergences) {
            if (divergence.getIndicator() == indicatorType) {
                for (BarTuple candle : divergence.getCandles()) {
                    double x = series.getBar(candle.getIndex()).getEndTime().toInstant().toEpochMilli();
                    double y = candle.getValue();

                    // Add the points for bullish and bearish divergences
                    if (divergence.getDirection() == DivergenceDirection.Bullish) {
                        bullishSeries.add(x, y);  // Add points to the bullish series
                    } else {
                        bearishSeries.add(x, y);  // Add points to the bearish series
                    }
                }
            }
        }

        // Add the series to the dataset
        divergenceDataset.addSeries(bullishSeries);
        divergenceDataset.addSeries(bearishSeries);

        // Set the dataset and the renderer
        plot.setDataset(1, divergenceDataset);

        // Customize the renderer for colors
        renderer.setSeriesPaint(0, Color.GREEN);  // Green for bullish divergence
        renderer.setSeriesPaint(1, Color.RED);    // Red for bearish divergence

        // Add renderer to the plot
        plot.setRenderer(1, renderer);
    }
}
