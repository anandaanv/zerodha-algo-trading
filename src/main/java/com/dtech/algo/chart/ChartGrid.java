package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.series.IntervalBarSeries;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * ChartGrid creates a grid of charts from multiple IntervalBarSeries
 * and combines them into a single image.
 */
@Slf4j
@Component
public class ChartGrid {

    private final MatplotChartCreator matplotChartCreator;
    private final ComplexChartCreator complexChartCreator;

    @Autowired
    public ChartGrid(MatplotChartCreator matplotChartCreator, ComplexChartCreator complexChartCreator) {
        this.matplotChartCreator = matplotChartCreator;
        this.complexChartCreator = complexChartCreator;
    }

    /**
     * Configuration for the chart grid
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GridConfig {
        private int columns; // Number of columns in the grid
        private int width; // Width of each chart in pixels
        private int height; // Height of each chart in pixels
        private int padding; // Padding between charts in pixels
        private boolean useMatplotlib; // Whether to use MatplotChartCreator or ComplexChartCreator
        private ChartConfig baseConfig; // Base configuration for all charts

        /**
         * Static method to create a default configuration
         */
        public static GridConfig defaultConfig() {
            return GridConfig.builder()
                .columns(2) // Default to 2 columns
                .width(6400) // Default width
                .height(4800) // Default height
                .padding(10) // Default padding
                .useMatplotlib(true) // Default to using MatplotChartCreator
                .baseConfig(new ChartConfig()) // Default chart config
                .build();
        }
    }

    /**
     * Creates a grid of charts from multiple IntervalBarSeries
     *
     * @param seriesList List of IntervalBarSeries to create charts for
     * @param gridConfig Configuration for the grid
     * @return Byte array containing the combined image
     */
    public byte[] createChartGrid(List<IntervalBarSeries> seriesList, GridConfig gridConfig) {
        return createChartGrid(seriesList, gridConfig, "charts/grid_chart.png");
    }
    
    /**
     * Creates a grid of charts from multiple IntervalBarSeries and saves it to a file
     *
     * @param seriesList List of IntervalBarSeries to create charts for
     * @param gridConfig Configuration for the grid
     * @param outputFilePath Path where to save the chart grid image (null if not saving to file)
     * @return Byte array containing the combined image
     */
    public byte[] createChartGrid(List<IntervalBarSeries> seriesList, GridConfig gridConfig, String outputFilePath) {
        if (seriesList == null || seriesList.isEmpty()) {
            log.error("Cannot create chart grid: series list is empty");
            return new byte[0];
        }

        // Apply defaults if gridConfig is null
        if (gridConfig == null) {
            gridConfig = GridConfig.defaultConfig();
        }

        // Calculate grid dimensions
        int columns = gridConfig.getColumns();
        int rows = (int) Math.ceil((double) seriesList.size() / columns);

        // Calculate total image dimensions
        int totalWidth = columns * gridConfig.getWidth() + (columns - 1) * gridConfig.getPadding();
        int totalHeight = rows * gridConfig.getHeight() + (rows - 1) * gridConfig.getPadding();

        // Create a new buffered image to hold the grid
        BufferedImage gridImage = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = gridImage.createGraphics();

        // Fill background with white
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, totalWidth, totalHeight);

        // Generate individual charts and place them in the grid
        List<byte[]> chartImages = new ArrayList<>();
        for (IntervalBarSeries series : seriesList) {
            try {
                // Create chart config for this series
                ChartConfig chartConfig = createChartConfig(series, gridConfig.getBaseConfig());
                
                // Generate chart image
                byte[] chartImageBytes;
                if (gridConfig.isUseMatplotlib()) {
                    chartImageBytes = matplotChartCreator.createChart(chartConfig);
                } else {
                    // For ComplexChartCreator, we need to convert JFreeChart to byte array
                    chartImageBytes = complexChartCreator.exportChartAsPNG(
                            complexChartCreator.createChart(chartConfig),
                            gridConfig.getWidth(),
                            gridConfig.getHeight()
                    );
                }
                chartImages.add(chartImageBytes);
            } catch (Exception e) {
                log.error("Failed to create chart for series: {}", series.getName(), e);
                // Add a placeholder for failed chart
                chartImages.add(createErrorPlaceholder(gridConfig.getWidth(), gridConfig.getHeight()));
            }
        }

        // Place the charts in the grid
        for (int i = 0; i < chartImages.size(); i++) {
            int row = i / columns;
            int col = i % columns;
            
            int x = col * (gridConfig.getWidth() + gridConfig.getPadding());
            int y = row * (gridConfig.getHeight() + gridConfig.getPadding());
            
            try {
                BufferedImage chartImage = ImageIO.read(new ByteArrayInputStream(chartImages.get(i)));
                g2d.drawImage(chartImage, x, y, null);
            } catch (IOException e) {
                log.error("Failed to draw chart at position ({}, {})", row, col, e);
            }
        }

        g2d.dispose();

        // Convert the combined image to byte array
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(gridImage, "png", outputStream);
            byte[] imageData = outputStream.toByteArray();
            
            // If outputFilePath is provided, save the image to that location
            if (outputFilePath != null && !outputFilePath.isEmpty()) {
                try {
                    File savedFile = new File(outputFilePath);
                    // Create parent directories if they don't exist
                    if (savedFile.getParentFile() != null) {
                        savedFile.getParentFile().mkdirs();
                    }
                    Files.write(savedFile.toPath(), imageData);
                    log.info("Chart grid saved to file: {}", outputFilePath);
                } catch (IOException e) {
                    log.error("Failed to save chart grid to file: {}", outputFilePath, e);
                }
            }
            
            return imageData;
        } catch (IOException e) {
            log.error("Failed to convert grid image to byte array", e);
            return new byte[0];
        }
    }

    /**
     * Creates a chart config for a specific series based on the base config
     *
     * @param series The series to create a chart for
     * @param baseConfig The base configuration to use
     * @return A new ChartConfig instance
     */
    private ChartConfig createChartConfig(IntervalBarSeries series, ChartConfig baseConfig) {
        // Create a new config based on the base config
        ChartConfig config = new ChartConfig();
        
        // Copy properties from base config if it's not null
        if (baseConfig != null) {
            config.setChartType(baseConfig.getChartType());
            config.setDisplayedBars(baseConfig.getDisplayedBars());
            config.setShowVolume(baseConfig.isShowVolume());
            config.setShowLegend(baseConfig.isShowLegend());
            
            // Copy indicators if any
            if (baseConfig.getIndicators() != null) {
                config.setIndicators(new ArrayList<>(baseConfig.getIndicators()));
            }
            
            // Copy additional options if any
            if (baseConfig.getAdditionalOptions() != null) {
                config.setAdditionalOptions(new java.util.HashMap<>(baseConfig.getAdditionalOptions()));
            }
        }
        
        // Set the series and title
        config.setBarSeries(series);
        
        // Create a more descriptive title based on the instrument and interval
        String title = series.getInstrument() + " - " + series.getInterval().toString();
        config.setTitle(title);
        
        return config;
    }

    /**
     * Creates a placeholder image for failed charts
     *
     * @param width Width of the placeholder
     * @param height Height of the placeholder
     * @return Byte array containing the placeholder image
     */
    private byte[] createErrorPlaceholder(int width, int height) {
        BufferedImage placeholder = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = placeholder.createGraphics();
        
        // Fill with light gray background
        g2d.setColor(new Color(240, 240, 240));
        g2d.fillRect(0, 0, width, height);
        
        // Draw error message
        g2d.setColor(Color.RED);
        g2d.setFont(new Font("Arial", Font.BOLD, 16));
        String errorMessage = "Chart generation failed";
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(errorMessage);
        g2d.drawString(errorMessage, (width - textWidth) / 2, height / 2);
        
        g2d.dispose();
        
        // Convert to byte array
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(placeholder, "png", outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            log.error("Failed to create error placeholder", e);
            return new byte[0];
        }
    }
}