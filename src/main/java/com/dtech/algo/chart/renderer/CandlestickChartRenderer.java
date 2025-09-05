package com.dtech.algo.chart.renderer;

import com.dtech.algo.chart.config.ChartConfig;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.data.xy.OHLCDataset;
import org.jfree.data.xy.XYDataset;

import java.awt.*;
import java.text.SimpleDateFormat;

/**
 * Renderer for candlestick charts.
 * Creates a candlestick chart with customizable appearance.
 */
public class CandlestickChartRenderer implements ChartRenderer {

    @Override
    public JFreeChart renderChart(XYDataset dataset, ChartConfig config) {
        if (!(dataset instanceof OHLCDataset)) {
            throw new IllegalArgumentException("Candlestick chart requires OHLCDataset");
        }
        
        OHLCDataset ohlcDataset = (OHLCDataset) dataset;
        
        // Create the axes
        DateAxis dateAxis = new DateAxis("Date");
        dateAxis.setDateFormatOverride(new SimpleDateFormat("yyyy-MM-dd"));
        
        NumberAxis priceAxis = new NumberAxis("Price");
        priceAxis.setAutoRangeIncludesZero(false);
        
        // Create the renderer
        CandlestickRenderer renderer = new CandlestickRenderer();
        
        // Configure candlestick appearance
        String upColor = config.getOption("upColor", "#00FF00");
        String downColor = config.getOption("downColor", "#FF0000");
        
        renderer.setUpPaint(Color.decode(upColor));
        renderer.setDownPaint(Color.decode(downColor));
        
        // Set candlestick width
        double candleWidth = 0.8;
        try {
            String widthStr = config.getOption("candleWidth", "0.8");
            candleWidth = Double.parseDouble(widthStr);
        } catch (NumberFormatException e) {
            // Use default if parsing fails
        }
        // Set width method (0 = fixed, 1 = relative to range)
        renderer.setAutoWidthMethod(0);
        renderer.setAutoWidthFactor(candleWidth);
        
        // Create the plot
        XYPlot plot = new XYPlot(ohlcDataset, dateAxis, priceAxis, renderer);
        
        // Configure plot appearance
        String gridColor = config.getOption("gridColor", "#CCCCCC");
        plot.setDomainGridlinePaint(Color.decode(gridColor));
        plot.setRangeGridlinePaint(Color.decode(gridColor));
        
        String plotBackgroundColor = config.getOption("plotBackgroundColor", "#FFFFFF");
        plot.setBackgroundPaint(Color.decode(plotBackgroundColor));
        
        // Create the chart
        JFreeChart chart = new JFreeChart(
                config.getTitle(),
                JFreeChart.DEFAULT_TITLE_FONT,
                plot,
                config.isShowLegend()
        );
        
        // Configure chart appearance
        String backgroundColor = config.getOption("backgroundColor", "#FFFFFF");
        chart.setBackgroundPaint(Color.decode(backgroundColor));
        
        return chart;
    }
}