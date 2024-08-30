package com.dtech.chart;

import com.dtech.ta.BarTuple;
import com.dtech.ta.TrendLineCalculated;
import com.dtech.ta.patterns.TrianglePattern;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class TriangleVisualizer {

    private final BarSeries series;

    public TriangleVisualizer(BarSeries series) {
        this.series = series;
    }

    public void visualizeTriangles(List<TrianglePattern> triangles) {
        XYSeriesCollection dataset = new XYSeriesCollection();
        XYSeries priceSeries = new XYSeries("Price");

        for (int i = 0; i < series.getBarCount(); i++) {
            Bar bar = series.getBar(i);
            priceSeries.add(i, bar.getClosePrice().doubleValue());
        }
        dataset.addSeries(priceSeries);

        int c = 0;
        for (TrianglePattern triangle : triangles) {
            addTrendLineToDataset(dataset, triangle.getHighTrendline(), "High Trendline" + c++);
            addTrendLineToDataset(dataset, triangle.getLowTrendline(), "Low Trendline" + c++);
        }

        JFreeChart chart = ChartFactory.createXYLineChart(
                "Descending Triangle Pattern",
                "Time",
                "Price",
                dataset,
                PlotOrientation.VERTICAL,
                false,
                true,
                false
        );

        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        plot.setRenderer(renderer);
        renderer.setSeriesPaint(0, Color.BLUE);
        renderer.setSeriesPaint(1, Color.RED);
        renderer.setSeriesPaint(2, Color.GREEN);

        JFrame frame = new JFrame();
        frame.setLayout(new BorderLayout());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(new ChartPanel(chart), BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);
        saveChartAsJPEG("MyTriangles", chart);
    }

    private void addTrendLineToDataset(XYSeriesCollection dataset, TrendLineCalculated trendline, String name) {
        XYSeries series = new XYSeries(name);
        for (BarTuple point : trendline.getPoints()) {
            series.add(point.getIndex(), point.getBar().getClosePrice().doubleValue());
        }
        dataset.addSeries(series);
    }


    public static void saveChartAsJPEG(String fileName, JFreeChart chart) {
        try {
            ChartUtils.saveChartAsJPEG(new File(fileName+ ".jpg"), chart, 800, 600);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
