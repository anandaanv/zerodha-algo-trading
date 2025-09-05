package com.dtech.algo.chart.renderer;

import com.dtech.algo.chart.config.ChartConfig;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYDataset;

import java.awt.*;
import java.text.SimpleDateFormat;

/**
 * Renderer for line charts.
 * Creates a simple line chart with customizable appearance.
 */
public class LineChartRenderer implements ChartRenderer {

    @Override
    public JFreeChart renderChart(XYDataset dataset, ChartConfig config) {
        // Create the chart
        JFreeChart chart = ChartFactory.createXYLineChart(
                config.getTitle(),
                "Date",
                "Price",
                dataset,
                PlotOrientation.VERTICAL,
                config.isShowLegend(),
                true,
                false
        );

        // Configure the plot
        XYPlot plot = chart.getXYPlot();
        
        // Configure the renderer
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setDefaultShapesVisible(false);
        renderer.setDefaultLinesVisible(true);
        
        // Set line color from config or use default
        String lineColor = config.getOption("priceLineColor", "#0000FF");
        renderer.setSeriesPaint(0, Color.decode(lineColor));
        
        // Set line thickness from config or use default
        String lineThickness = config.getOption("lineThickness", "1.5");
        float thickness = Float.parseFloat(lineThickness);
        renderer.setSeriesStroke(0, new BasicStroke(thickness));
        
        plot.setRenderer(renderer);
        
        // Configure the domain axis (x-axis)
        DateAxis dateAxis = new DateAxis("Date");
        dateAxis.setDateFormatOverride(new SimpleDateFormat("yyyy-MM-dd"));
        plot.setDomainAxis(dateAxis);
        
        // Configure the range axis (y-axis)
        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setAutoRangeIncludesZero(false);
        
        // Set background colors
        String backgroundColor = config.getOption("backgroundColor", "#FFFFFF");
        chart.setBackgroundPaint(Color.decode(backgroundColor));
        
        String plotBackgroundColor = config.getOption("plotBackgroundColor", "#FFFFFF");
        plot.setBackgroundPaint(Color.decode(plotBackgroundColor));
        
        String gridColor = config.getOption("gridColor", "#CCCCCC");
        plot.setDomainGridlinePaint(Color.decode(gridColor));
        plot.setRangeGridlinePaint(Color.decode(gridColor));
        
        return chart;
    }
}