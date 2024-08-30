package com.dtech.ta.visualize;

import com.dtech.ta.divergences.Divergence;
import com.dtech.ta.divergences.IndicatorType;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.List;

import static com.dtech.ta.visualize.VisualizerHelper.plotDivergences;

public class RSIVisualizer {
    public static XYPlot createRSIPlot(BarSeries series, List<Divergence> divergences) {
        XYPlot plot = new XYPlot();

        // Create RSI dataset and renderer
        XYSeries rsiSeries = new XYSeries("RSI");
        RSIIndicator rsiIndicator = new RSIIndicator(new ClosePriceIndicator(series), 14);
        for (int i = 0; i < series.getBarCount(); i++) {
            rsiSeries.add(series.getBar(i).getEndTime().toInstant().toEpochMilli(), rsiIndicator.getValue(i).doubleValue());
        }
        XYSeriesCollection dataset = new XYSeriesCollection(rsiSeries);
        plot.setDataset(dataset);
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        plot.setRenderer(renderer);

        // Plot divergences on RSI chart
        plotDivergences(plot, divergences, IndicatorType.RSI, series);

        // Initialize the range axis and set range for RSI
        NumberAxis rangeAxis = new NumberAxis("RSI");
        rangeAxis.setRange(0, 100);
        plot.setRangeAxis(rangeAxis);

        return plot;
    }

}
