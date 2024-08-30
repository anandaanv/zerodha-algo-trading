package com.dtech.chart;

import com.dtech.chart.TriangleVisualizer;
import com.dtech.ta.BarTuple;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;

import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class ChartCreator {

    public JFreeChart createChart(BarSeries series, List<BarTuple> maxima, List<BarTuple> minima) {
        XYSeriesCollection dataset = new XYSeriesCollection();

        XYSeries highSeries = new XYSeries("High");
        XYSeries lowSeries = new XYSeries("Low");
        XYSeries maxSeries = new XYSeries("Significant Maxima");
        XYSeries minSeries = new XYSeries("Significant Minima");

        for (int i = 0; i < series.getBarCount(); i++) {
            Bar bar = series.getBar(i);
            highSeries.add(i, bar.getHighPrice().doubleValue());
            lowSeries.add(i, bar.getLowPrice().doubleValue());
        }

        for (BarTuple tuple : maxima) {
            Bar bar = tuple.getBar();
            int index = tuple.getIndex();
            maxSeries.add(index, bar.getHighPrice().doubleValue());
        }

        for (BarTuple tuple : minima) {
            Bar bar = tuple.getBar();
            int index = tuple.getIndex();
            minSeries.add(index, bar.getLowPrice().doubleValue());
        }

        dataset.addSeries(highSeries);
        dataset.addSeries(lowSeries);
        dataset.addSeries(maxSeries);
        dataset.addSeries(minSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                "OHLC Chart", "Index", "Price",
                dataset, PlotOrientation.VERTICAL,
                true, true, false
        );

        XYPlot plot = chart.getXYPlot();
        plot.setDomainPannable(true);
        plot.setRangePannable(true);

        TriangleVisualizer.saveChartAsJPEG("Maxima_minima", chart);

        return chart;
    }
}
