package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;
import com.dtech.algo.chart.indicator.IndicatorCategory;
import com.dtech.algo.chart.indicator.IndicatorCategoryService;
import com.dtech.algo.chart.renderer.CandlestickChartRenderer;
import com.dtech.algo.chart.renderer.ChartRenderer;
import com.dtech.algo.chart.renderer.ChartRendererFactory;
import com.dtech.algo.series.IntervalBarSeries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.time.Day;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.jfree.data.xy.DefaultOHLCDataset;
import org.jfree.data.xy.OHLCDataItem;
import org.jfree.data.xy.XYDataset;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.indicators.CachedIndicator;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates complex charts with indicators based on configuration.
 * This is a core component of the chart service that translates ChartConfig objects
 * into rendered JFreeChart instances.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComplexChartCreator {

    private final ChartRendererFactory rendererFactory;
    private final IndicatorCategoryService indicatorCategoryService;

    /**
     * Create a JFreeChart from the provided configuration
     * @param config Chart configuration with bar series and indicators
     * @return A configured JFreeChart object
     */
    public JFreeChart createChart(ChartConfig config) {
        if (!config.isValid()) {
            throw new IllegalArgumentException("Invalid chart configuration");
        }
        
        // Validate that bar series has data
        if (config.getBarSeries().isEmpty()) {
            throw new IllegalArgumentException("Chart cannot be created with empty bar series");
        }

        // Create dataset from bar series
        XYDataset dataset = createDataset(config.getBarSeries().getFirst(), config.getEffectiveDisplayedBars());
        
        // Validate that dataset has data
        if (dataset instanceof DefaultOHLCDataset) {
            DefaultOHLCDataset ohlcDataset = (DefaultOHLCDataset) dataset;
            if (ohlcDataset.getItemCount(0) == 0) {
                throw new IllegalArgumentException("Chart cannot be created with empty dataset");
            }
        }
        
        // Create chart using appropriate renderer
        ChartRenderer renderer = rendererFactory.getRenderer(config.getChartType());
        JFreeChart chart = renderer
                .renderChart(dataset, config);
        
        // Add indicators if present
        if (config.getIndicators() != null && !config.getIndicators().isEmpty()) {
            addIndicatorsToChart(chart, config);
        }
        
        // Add volume if requested
        if (config.isShowVolume()) {
            addVolumePanel(chart, config);
        }
        
        return chart;
    }

    /**
     * Save chart as JPEG file
     * @param chart The JFreeChart to save
     * @param filename The filename to save to (without extension)
     * @param width The width of the image
     * @param height The height of the image
     */
    public void saveChartAsJPEG(JFreeChart chart, String filename, int width, int height) {
        if (chart == null) {
            log.error("Cannot save null chart as JPEG");
            return;
        }
        
        try {
            File file = new File(filename + ".jpg");
            ChartUtils.saveChartAsJPEG(file, chart, width, height);
        } catch (IOException e) {
            log.error("Error saving chart as JPEG", e);
        }
    }

    /**
     * Export chart as PNG byte array
     * @param chart The JFreeChart to export
     * @param width Desired width of the image
     * @param height Desired height of the image
     * @return PNG image as byte array
     */
    public byte[] exportChartAsPNG(JFreeChart chart, int width, int height) {
        if (chart == null) {
            log.error("Cannot export null chart as PNG");
            throw new IllegalArgumentException("Cannot export null chart as PNG");
        }
        
        if (width <= 0 || height <= 0) {
            log.error("Invalid dimensions for chart image: {}x{}", width, height);
            throw new IllegalArgumentException("Invalid dimensions for chart image");
        }
        
        try {
            // Create the buffered image first to ensure it's valid
            BufferedImage bufferedImage = chart.createBufferedImage(width, height);
            // Now that we have a valid image, write it to the output stream
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ChartUtils.writeBufferedImageAsPNG(baos, bufferedImage);
            
            // Also save a copy to disk for reference
            try {
                saveChartAsPng("chart-all", "charts", chart);
            } catch (Exception e) {
                // Don't fail the entire operation if saving to disk fails
                log.warn("Failed to save reference chart to disk: {}", e.getMessage());
            }
            
            return baos.toByteArray();
        } catch (IllegalStateException e) {
            // Re-throw IllegalStateException directly
            throw e;
        } catch (Exception e) {
            log.error("Error exporting chart as PNG: {}", e.getMessage());
            throw new IllegalStateException("Error exporting chart as PNG", e);
        }
    }

    public void saveChartAsPng(String filename, String directory, JFreeChart chart) {

        File imageFile = new File(directory + "/" + filename + ".png");
        imageFile.getParentFile().mkdirs(); // Ensure the directory exists

        try {
            // Create the buffered image first to ensure it's valid
            BufferedImage bufferedImage = chart.createBufferedImage(4000, 2000);

            // Now save the chart using the validated buffered image
            ChartUtils.saveChartAsPNG(imageFile, chart, 4000, 2000);
            log.info("Chart saved to {}", imageFile.getAbsolutePath());
        } catch (IllegalStateException e) {
            // Re-throw IllegalStateException directly
            throw e;
        } catch (IOException e) {
            log.error("Error saving chart to PNG: {}", e.getMessage());
            throw new IllegalStateException("Error saving chart to PNG", e);
        }
    }

    /**
     * Add indicators to a chart
     * @param chart The JFreeChart to add indicators to
     * @param config Configuration containing the indicators
     */
    private void addIndicatorsToChart(JFreeChart chart, ChartConfig config) {
        XYPlot mainPlot = chart.getXYPlot();
        List<CachedIndicator<?>> overlayIndicators = new ArrayList<>();
        List<CachedIndicator<?>> separatePanelIndicators = new ArrayList<>();
        
        // Categorize indicators
        for (CachedIndicator<?> indicator : config.getIndicators()) {
            IndicatorCategory category = indicatorCategoryService.categorizeIndicator(indicator);
            log.debug("Indicator {} categorized as {}", indicator, category);
            if (category == IndicatorCategory.OVERLAY) {
                overlayIndicators.add(indicator);
            } else {
                separatePanelIndicators.add(indicator);
            }
        }
        
        log.debug("Found {} overlay indicators and {} separate panel indicators", 
                 overlayIndicators.size(), separatePanelIndicators.size());
        
        // Add overlay indicators to main plot
        for (CachedIndicator<?> indicator : overlayIndicators) {
            addOverlayIndicator(mainPlot, indicator, config.getBarSeries().getFirst());
        }
        
        // Add separate panel indicators
        for (CachedIndicator<?> indicator : separatePanelIndicators) {
            addSeparatePanelIndicator(chart, indicator, config.getBarSeries().getFirst());
        }
    }

    /**
     * Add an overlay indicator to the main plot
     * @param plot The main plot
     * @param indicator The indicator to add
     * @param barSeries The bar series
     */
    private void addOverlayIndicator(XYPlot plot, CachedIndicator<?> indicator, IntervalBarSeries barSeries) {
        log.debug("Adding overlay indicator: {}", indicator);
        
        // Create a time series for the indicator
        TimeSeriesCollection dataset = new TimeSeriesCollection();
        TimeSeries series = new TimeSeries(indicator.toString());
        
        int validPointsCount = 0;
        
        // Add data points
        for (int i = 0; i < barSeries.getBarCount(); i++) {
            Bar bar = barSeries.getBar(i);
            ZonedDateTime dateTime = bar.getEndTime();
            Date date = Date.from(dateTime.toInstant());
            
            // Get the indicator value - handle different return types
            Object value = indicator.getValue(i);
            double doubleValue;
            
            // Handle different types of numeric values
            try {
                if (value instanceof Number) {
                    doubleValue = ((Number) value).doubleValue();
                    validPointsCount++;
                } else if (value instanceof java.math.BigDecimal) {
                    doubleValue = ((java.math.BigDecimal) value).doubleValue();
                    validPointsCount++;
                } else if (value != null) {
                    // Try to parse the string representation
                    doubleValue = Double.parseDouble(value.toString());
                    validPointsCount++;
                } else {
                    // Skip null values
                    log.warn("Indicator value is null for index {}", i);
                    continue;
                }
            } catch (NumberFormatException e) {
                // Skip this point if we can't convert to a number
                log.warn("Indicator value is not a number: {}", value);
                continue;
            }
            
            series.add(new Day(date), doubleValue);
        }
        
        log.debug("Added {} valid data points to indicator series", validPointsCount);
        
        dataset.addSeries(series);
        
        // Add the dataset to the plot
        int datasetIndex = plot.getDatasetCount();
        log.debug("Adding indicator dataset at index {}", datasetIndex);
        plot.setDataset(datasetIndex, dataset);
        
        // Configure renderer for the indicator
        org.jfree.chart.renderer.xy.XYLineAndShapeRenderer lineRenderer = 
            new org.jfree.chart.renderer.xy.XYLineAndShapeRenderer(true, false);
        
        // Set a distinctive color for the indicator line
        lineRenderer.setSeriesPaint(0, java.awt.Color.BLUE);
        lineRenderer.setSeriesStroke(0, new java.awt.BasicStroke(2.0f));
        
        plot.setRenderer(datasetIndex, lineRenderer);
        log.debug("Configured renderer for indicator at dataset index {}", datasetIndex);
    }

    /**
     * Add a separate panel indicator below the main chart
     * @param chart The chart
     * @param indicator The indicator to add
     * @param barSeries The bar series
     */
    private void addSeparatePanelIndicator(JFreeChart chart, CachedIndicator<?> indicator, IntervalBarSeries barSeries) {
        log.debug("Adding separate panel indicator: {}", indicator);
        
        // Create a time series for the indicator
        TimeSeriesCollection dataset = new TimeSeriesCollection();
        TimeSeries series = new TimeSeries(indicator.toString());
        
        int validPointsCount = 0;
        
        // Add data points
        for (int i = 0; i < barSeries.getBarCount(); i++) {
            Bar bar = barSeries.getBar(i);
            ZonedDateTime dateTime = bar.getEndTime();
            Date date = Date.from(dateTime.toInstant());
            
            // Get the indicator value - handle different return types
            Object value = indicator.getValue(i);
            double doubleValue;
            
            // Handle different types of numeric values
            try {
                if (value instanceof Number) {
                    doubleValue = ((Number) value).doubleValue();
                    validPointsCount++;
                } else if (value instanceof java.math.BigDecimal) {
                    doubleValue = ((java.math.BigDecimal) value).doubleValue();
                    validPointsCount++;
                } else if (value != null) {
                    // Try to parse the string representation
                    doubleValue = Double.parseDouble(value.toString());
                    validPointsCount++;
                } else {
                    // Skip null values
                    log.warn("Indicator value is null for index {}", i);
                    continue;
                }
            } catch (NumberFormatException e) {
                // Skip this point if we can't convert to a number
                log.warn("Indicator value is not a number: {}", value);
                continue;
            }
            
            series.add(new Day(date), doubleValue);
        }
        
        log.debug("Added {} valid data points to separate panel indicator series", validPointsCount);
        dataset.addSeries(series);
        
        // For now, we'll add this as an overlay indicator to demonstrate it's working
        // In a full implementation, we would create a separate panel
        XYPlot mainPlot = chart.getXYPlot();
        int datasetIndex = mainPlot.getDatasetCount();
        mainPlot.setDataset(datasetIndex, dataset);
        
        // Configure renderer for the indicator with a different style to distinguish it
        org.jfree.chart.renderer.xy.XYLineAndShapeRenderer lineRenderer = 
            new org.jfree.chart.renderer.xy.XYLineAndShapeRenderer(true, false);
        
        // Use a different color for separate panel indicators (shown as overlay for now)
        lineRenderer.setSeriesPaint(0, java.awt.Color.RED);
        lineRenderer.setSeriesStroke(0, new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND, 
                                                               java.awt.BasicStroke.JOIN_ROUND, 
                                                               1.0f, new float[] {6.0f, 3.0f}, 0.0f));
        
        mainPlot.setRenderer(datasetIndex, lineRenderer);
        log.debug("Added separate panel indicator as overlay (temporary solution) at dataset index {}", datasetIndex);
        
        // TODO: In a future implementation, create a proper separate panel for this indicator
    }

    /**
     * Add a volume panel below the main chart
     * @param chart The chart
     * @param config The chart configuration
     */
    private void addVolumePanel(JFreeChart chart, ChartConfig config) {
        log.debug("Adding volume panel");
        
        IntervalBarSeries barSeries = config.getBarSeries().getFirst();
        
        // Create a time series for the volume data
        TimeSeriesCollection volumeDataset = new TimeSeriesCollection();
        TimeSeries volumeSeries = new TimeSeries("Volume");
        
        // Add volume data points
        for (int i = 0; i < barSeries.getBarCount(); i++) {
            Bar bar = barSeries.getBar(i);
            ZonedDateTime dateTime = bar.getEndTime();
            Date date = Date.from(dateTime.toInstant());
            double volume = bar.getVolume().doubleValue();
            
            volumeSeries.add(new Day(date), volume);
        }
        
        volumeDataset.addSeries(volumeSeries);
        
        // For now, we'll add this as an additional dataset to the main plot
        // In a full implementation, we would create a separate panel
        XYPlot mainPlot = chart.getXYPlot();
        int datasetIndex = mainPlot.getDatasetCount();
        mainPlot.setDataset(datasetIndex, volumeDataset);
        
        // Configure renderer for the volume data
        org.jfree.chart.renderer.xy.XYBarRenderer volumeRenderer = new org.jfree.chart.renderer.xy.XYBarRenderer();
        volumeRenderer.setShadowVisible(false);
        volumeRenderer.setBarPainter(new org.jfree.chart.renderer.xy.StandardXYBarPainter());
        volumeRenderer.setSeriesPaint(0, new java.awt.Color(0, 0, 128, 50)); // Semi-transparent navy blue
        
        mainPlot.setRenderer(datasetIndex, volumeRenderer);
        
        // Map the volume dataset to the right axis if it exists, otherwise create it
        org.jfree.chart.axis.NumberAxis volumeAxis = null;
        
        // Check if there's already a right-side axis we can use
        if (mainPlot.getRangeAxisCount() > 1 && mainPlot.getRangeAxis(1) != null) {
            volumeAxis = (org.jfree.chart.axis.NumberAxis) mainPlot.getRangeAxis(1);
        } else {
            // Create a new axis for volume
            volumeAxis = new org.jfree.chart.axis.NumberAxis("Volume");
            volumeAxis.setAutoRangeIncludesZero(true);
            mainPlot.setRangeAxis(1, volumeAxis);
        }
        
        // Map the volume dataset to the volume axis
        mainPlot.mapDatasetToRangeAxis(datasetIndex, 1);
        
        log.debug("Added volume data as overlay with separate axis (temporary solution)");
        
        // TODO: In a future implementation, create a proper separate panel for volume
    }

    /**
     * Create a dataset from bar series
     * @param barSeries The bar series to use
     * @param displayedBars Number of bars to display
     * @return A dataset appropriate for the chart type
     */
    private XYDataset createDataset(IntervalBarSeries barSeries, int displayedBars) {
        // Determine the start index based on displayedBars
        int startIndex = Math.max(0, barSeries.getBarCount() - displayedBars);
        int endIndex = barSeries.getBarCount();
        
        // Create OHLC data items
        List<OHLCDataItem> dataItems = new ArrayList<>();
        
        for (int i = startIndex; i < endIndex; i++) {
            Bar bar = barSeries.getBar(i);
            Date date = Date.from(bar.getEndTime().toInstant());
            double open = bar.getOpenPrice().doubleValue();
            double high = bar.getHighPrice().doubleValue();
            double low = bar.getLowPrice().doubleValue();
            double close = bar.getClosePrice().doubleValue();
            double volume = bar.getVolume().doubleValue();
            
            dataItems.add(new OHLCDataItem(date, open, high, low, close, volume));
        }
        
        // Convert to array
        OHLCDataItem[] itemArray = dataItems.toArray(new OHLCDataItem[0]);
        
        // Create dataset
        return new DefaultOHLCDataset(barSeries.getName(), itemArray);
    }

    /**
     * Generate a summary of the data for metadata
     * @param barSeries The bar series
     * @return A map of summary statistics
     */
    private Map<String, Object> generateDataSummary(IntervalBarSeries barSeries) {
        Map<String, Object> summary = new HashMap<>();
        
        if (barSeries.getBarCount() > 0) {
            Bar firstBar = barSeries.getBar(0);
            Bar lastBar = barSeries.getLastBar();
            
            summary.put("symbol", barSeries.getName());
            summary.put("interval", barSeries.getInterval().toString());
            summary.put("startDate", firstBar.getEndTime().toString());
            summary.put("endDate", lastBar.getEndTime().toString());
            summary.put("barCount", barSeries.getBarCount());
            
            // Calculate min, max, avg prices
            double minPrice = Double.MAX_VALUE;
            double maxPrice = Double.MIN_VALUE;
            double sumClose = 0;
            
            for (int i = 0; i < barSeries.getBarCount(); i++) {
                Bar bar = barSeries.getBar(i);
                double low = bar.getLowPrice().doubleValue();
                double high = bar.getHighPrice().doubleValue();
                double close = bar.getClosePrice().doubleValue();
                
                minPrice = Math.min(minPrice, low);
                maxPrice = Math.max(maxPrice, high);
                sumClose += close;
            }
            
            double avgClose = sumClose / barSeries.getBarCount();
            
            summary.put("minPrice", minPrice);
            summary.put("maxPrice", maxPrice);
            summary.put("avgClose", avgClose);
            summary.put("lastClose", lastBar.getClosePrice().doubleValue());
        }
        
        return summary;
    }
}