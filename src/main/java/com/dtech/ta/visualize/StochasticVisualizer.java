package com.dtech.ta.visualize;

import com.dtech.ta.divergences.Divergence;
import com.dtech.ta.divergences.IndicatorType;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;

import java.util.List;

import static com.dtech.ta.visualize.VisualizerHelper.plotDivergences;

public class StochasticVisualizer {

    public static XYPlot createStochasticPlot(BarSeries series, List<Divergence> divergences) {
        XYPlot plot = new XYPlot();

        // Create Stochastic dataset and renderer
        XYSeries stochasticSeries = new XYSeries("Stochastic");
        StochasticOscillatorKIndicator stochasticIndicator = new StochasticOscillatorKIndicator(series, 14);
        for (int i = 0; i < series.getBarCount(); i++) {
            stochasticSeries.add(series.getBar(i).getEndTime().toInstant().toEpochMilli(), stochasticIndicator.getValue(i).doubleValue());
        }
        XYSeriesCollection dataset = new XYSeriesCollection(stochasticSeries);
        plot.setDataset(dataset);
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        plot.setRenderer(renderer);

        // Plot divergences on Stochastic chart
        plotDivergences(plot, divergences, IndicatorType.STOCH, series);

        // Initialize the range axis and set range for Stochastic
        NumberAxis rangeAxis = new NumberAxis("Stochastic");
        rangeAxis.setRange(0, 100);
        plot.setRangeAxis(rangeAxis);

        return plot;
    }
}
