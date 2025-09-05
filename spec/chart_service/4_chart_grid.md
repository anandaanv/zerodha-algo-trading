# ChartGrid Specification

## Overview

The `ChartGrid` class is responsible for arranging multiple charts in a grid layout. It provides functionality to combine individual charts into a single image with a customizable layout. The implementation focuses on creating chart arrangements that are both visually effective for human analysis and semantically structured for AI interpretation.

## Class Definition

```java
package com.dtech.algo.chart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.HorizontalAlignment;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.ChartUtils;
import org.jfree.graphics2d.svg.SVGGraphics2D;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChartGrid {

    private final MetadataService metadataService;

    /**
     * Arrange multiple charts in a grid layout
     * @param charts List of individual charts
     * @param title Overall title for the combined chart
     * @return A combined chart with multiple panels
     */
    public JFreeChart arrangeCharts(List<JFreeChart> charts, String title) {
        // Calculate grid dimensions based on number of charts
        int rows = calculateRows(charts.size());
        int columns = calculateColumns(charts.size(), rows);

        // Create chart arrangement
        // Implementation details
    }

    /**
     * Export a grid of charts as SVG with semantic metadata for AI interpretation
     * @param charts List of individual charts
     * @param title Overall title for the combined chart
     * @param configs List of configurations for each chart
     * @return SVG representation of the combined chart with metadata
     */
    public String exportChartsAsSVG(List<JFreeChart> charts, String title, List<ChartConfig> configs) {
        // Implementation details
        // 1. Create combined chart
        // 2. Export to SVG with proper structure
        // 3. Add semantic metadata for each subchart
        // 4. Return SVG as string
    }

    /**
     * Export a grid of charts as PNG
     * @param charts List of individual charts
     * @param title Overall title for the combined chart
     * @param width Desired width of the image
     * @param height Desired height of the image
     * @return PNG image as byte array
     */
    public byte[] exportChartsAsPNG(List<JFreeChart> charts, String title, int width, int height) {
        // Implementation details
        // 1. Create combined chart
        // 2. Export to PNG
        // 3. Return byte array
    }

    /**
     * Calculate the number of rows needed
     * @param chartCount Number of charts
     * @return Number of rows (max 2)
     */
    private int calculateRows(int chartCount) {
        return Math.min(2, (int) Math.ceil(chartCount / 2.0));
    }

    /**
     * Calculate the number of columns needed
     * @param chartCount Number of charts
     * @param rows Number of rows
     * @return Number of columns
     */
    private int calculateColumns(int chartCount, int rows) {
        return (int) Math.ceil(chartCount / (double) rows);
    }

    /**
     * Add relationship metadata between charts for AI interpretation
     * @param svgGraphics SVG graphics object
     * @param configs List of chart configurations
     */
    private void addRelationshipMetadata(SVGGraphics2D svgGraphics, List<ChartConfig> configs) {
        // Implementation details
        // 1. Add relationships between charts (e.g., same instrument, different timeframes)
        // 2. Add overall data summary
        // 3. Add cross-chart pattern information
    }
}
```

## Implementation Details

The `ChartGrid` class in the chart package will:

1. **Calculate the optimal layout** based on the number of charts
   - Use at most 2 rows
   - Calculate columns based on chart count and row limit
   - Support 1-row layout for 1-2 charts

2. **Arrange multiple charts in a 2xN grid layout**
   - Create a combined chart with multiple subplots
   - Position each chart in its appropriate grid cell
   - Handle empty cells if the number of charts is odd
   - Support different chart types in the same grid

3. **Handle proper scaling and sizing of the combined chart**
   - Set appropriate overall dimensions
   - Ensure consistent width/height ratios
   - Scale individual charts proportionally
   - Adjust dimensions for optimal AI visibility

4. **Add titles and borders between charts**
   - Add main title for the combined chart
   - Preserve individual chart titles as subtitles
   - Add borders to visually separate charts
   - Include clear labels identifying each chart's purpose

5. **Ensure consistent styling across all charts in the grid**
   - Apply uniform styling to axis labels
   - Use consistent font sizes
   - Align charts properly
   - Use consistent color schemes across related charts

6. **Optimize for AI interpretation**
   - Implement proper SVG structure with semantic grouping
   - Add metadata describing relationships between charts
   - Include explicit chart indexes and positions
   - Provide overall data context in metadata
   - Ensure each subchart is clearly identified in the SVG structure

7. **Support multiple export formats**
   - Primary: SVG format with rich metadata for AI interpretation
   - Secondary: PNG/JPEG for human viewing
   - Tertiary: Data summary in JSON format

## Grid Layout Logic

The service will arrange charts in a 2-row grid with as many columns as needed based on the number of charts. For example:

- 1-2 charts: 1 row x 1-2 columns
- 3-4 charts: 2 rows x 2 columns
- 5-6 charts: 2 rows x 3 columns

The layout algorithm will fill the grid from left to right, top to bottom.

## Key Methods

### arrangeCharts

This is the main method that combines multiple charts into a single grid:

1. Calculate the number of rows and columns needed
2. Create a combined chart with a grid layout
3. Add each individual chart to the appropriate grid cell
4. Configure the overall appearance (title, borders, background)
5. Return the combined chart

### calculateRows and calculateColumns

These helper methods determine the grid dimensions:

- `calculateRows`: Returns at most 2 rows, based on the chart count
- `calculateColumns`: Calculates how many columns are needed given the number of charts and rows

## Example Implementation

The implementation will use JFreeChart's built-in capabilities for creating combined charts, but will extend them to provide a more polished and consistent appearance:

```java
public JFreeChart arrangeCharts(List<JFreeChart> charts, String title) {
    if (charts == null || charts.isEmpty()) {
        throw new IllegalArgumentException("Chart list cannot be null or empty");
    }

    // If only one chart, return it directly with updated title
    if (charts.size() == 1) {
        JFreeChart chart = charts.get(0);
        chart.setTitle(title);
        return chart;
    }

    // Calculate grid dimensions
    int rows = calculateRows(charts.size());
    int columns = calculateColumns(charts.size(), rows);

    // Create a panel to hold all charts
    JPanel panel = new JPanel(new GridLayout(rows, columns));

    // Add each chart to the panel
    for (JFreeChart chart : charts) {
        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(new Dimension(400, 300));
        panel.add(chartPanel);
    }

    // Fill any empty cells if needed
    int emptyCells = (rows * columns) - charts.size();
    for (int i = 0; i < emptyCells; i++) {
        panel.add(new JPanel());
    }

    // Create a new chart from the panel
    JFreeChart combinedChart = new JFreeChart(panel);

    // Set the title
    TextTitle textTitle = new TextTitle(title);
    textTitle.setFont(new Font("SansSerif", Font.BOLD, 18));
    textTitle.setPosition(RectangleEdge.TOP);
    textTitle.setHorizontalAlignment(HorizontalAlignment.CENTER);
    combinedChart.setTitle(textTitle);

    return combinedChart;
}
```

This implementation is a simplified version for illustration purposes. The actual implementation will use JFreeChart's more sophisticated mechanisms for creating combined charts.
