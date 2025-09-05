package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;
import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ChartGridTest {

    @Mock
    private MatplotChartCreator matplotChartCreator;

    @Mock
    private ComplexChartCreator complexChartCreator;

    @InjectMocks
    private ChartGrid chartGrid;

    private List<IntervalBarSeries> testSeriesList;

    @BeforeEach
    void setUp() throws Exception {
        // Create test data
        testSeriesList = new ArrayList<>();
        
        // Create first series
        BaseBarSeries baseSeries1 = new BaseBarSeries("AAPL");
        baseSeries1.addBar(createTestBar(ZonedDateTime.now().minusDays(3), 150.0, 155.0, 148.0, 153.0, 1000000));
        baseSeries1.addBar(createTestBar(ZonedDateTime.now().minusDays(2), 153.0, 158.0, 152.0, 157.0, 1200000));
        baseSeries1.addBar(createTestBar(ZonedDateTime.now().minusDays(1), 157.0, 160.0, 155.0, 159.0, 1500000));
        IntervalBarSeries series1 = new ExtendedBarSeries(baseSeries1, Interval.Day, SeriesType.EQUITY, "AAPL");
        testSeriesList.add(series1);
        
        // Create second series
        BaseBarSeries baseSeries2 = new BaseBarSeries("MSFT");
        baseSeries2.addBar(createTestBar(ZonedDateTime.now().minusDays(3), 250.0, 255.0, 248.0, 253.0, 800000));
        baseSeries2.addBar(createTestBar(ZonedDateTime.now().minusDays(2), 253.0, 258.0, 252.0, 257.0, 900000));
        baseSeries2.addBar(createTestBar(ZonedDateTime.now().minusDays(1), 257.0, 260.0, 255.0, 259.0, 1000000));
        IntervalBarSeries series2 = new ExtendedBarSeries(baseSeries2, Interval.Day, SeriesType.EQUITY, "MSFT");
        testSeriesList.add(series2);
        
        // Create third series
        BaseBarSeries baseSeries3 = new BaseBarSeries("GOOGL");
        baseSeries3.addBar(createTestBar(ZonedDateTime.now().minusDays(3), 2500.0, 2550.0, 2480.0, 2530.0, 500000));
        baseSeries3.addBar(createTestBar(ZonedDateTime.now().minusDays(2), 2530.0, 2580.0, 2520.0, 2570.0, 600000));
        baseSeries3.addBar(createTestBar(ZonedDateTime.now().minusDays(1), 2570.0, 2600.0, 2550.0, 2590.0, 700000));
        IntervalBarSeries series3 = new ExtendedBarSeries(baseSeries3, Interval.Day, SeriesType.EQUITY, "GOOGL");
        testSeriesList.add(series3);
        
        // Create fourth series
        BaseBarSeries baseSeries4 = new BaseBarSeries("AMZN");
        baseSeries4.addBar(createTestBar(ZonedDateTime.now().minusDays(3), 3200.0, 3250.0, 3180.0, 3230.0, 400000));
        baseSeries4.addBar(createTestBar(ZonedDateTime.now().minusDays(2), 3230.0, 3280.0, 3220.0, 3270.0, 450000));
        baseSeries4.addBar(createTestBar(ZonedDateTime.now().minusDays(1), 3270.0, 3300.0, 3250.0, 3290.0, 500000));
        IntervalBarSeries series4 = new ExtendedBarSeries(baseSeries4, Interval.Day, SeriesType.EQUITY, "AMZN");
        testSeriesList.add(series4);
        
        // Mock the chart creators to return test image data
        when(matplotChartCreator.createChart(any(ChartConfig.class))).thenReturn(createTestImageData());
        when(complexChartCreator.createChart(any(ChartConfig.class))).thenReturn(null); // JFreeChart object
        when(complexChartCreator.exportChartAsPNG(any(), anyInt(), anyInt())).thenReturn(createTestImageData());
    }
    
    @Test
    void testCreateChartGridWithMatplotlib() throws Exception {
        // Create grid config
        ChartGrid.GridConfig gridConfig = ChartGrid.GridConfig.builder()
                .columns(2)
                .width(400)
                .height(300)
                .padding(10)
                .useMatplotlib(true)
                .baseConfig(createBaseConfig())
                .build();
        
        // Create chart grid
        byte[] result = chartGrid.createChartGrid(testSeriesList, gridConfig);
        
        // Verify the result
        assertNotNull(result);
        assertTrue(result.length > 0);
        
        // Verify that MatplotChartCreator was called for each series
        verify(matplotChartCreator, times(testSeriesList.size())).createChart(any(ChartConfig.class));
        
        // Verify that ComplexChartCreator was not called
        verify(complexChartCreator, never()).createChart(any(ChartConfig.class));
        verify(complexChartCreator, never()).exportChartAsPNG(any(), anyInt(), anyInt());
        
        // Verify the image can be read
        assertDoesNotThrow(() -> {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(result));
            assertNotNull(image);
            
            // Verify grid dimensions
            int expectedWidth = 2 * 400 + 10; // 2 columns * width + padding
            int expectedHeight = 2 * 300 + 10; // 2 rows * height + padding
            assertEquals(expectedWidth, image.getWidth());
            assertEquals(expectedHeight, image.getHeight());
        });
    }
    
    @Test
    void testCreateChartGridWithComplexChartCreator() throws Exception {
        // Create grid config
        ChartGrid.GridConfig gridConfig = ChartGrid.GridConfig.builder()
                .columns(2)
                .width(400)
                .height(300)
                .padding(10)
                .useMatplotlib(false)
                .baseConfig(createBaseConfig())
                .build();
        
        // Create chart grid
        byte[] result = chartGrid.createChartGrid(testSeriesList, gridConfig);
        
        // Verify the result
        assertNotNull(result);
        assertTrue(result.length > 0);
        
        // Verify that ComplexChartCreator was called for each series
        verify(complexChartCreator, times(testSeriesList.size())).createChart(any(ChartConfig.class));
        verify(complexChartCreator, times(testSeriesList.size())).exportChartAsPNG(any(), anyInt(), anyInt());
        
        // Verify that MatplotChartCreator was not called
        verify(matplotChartCreator, never()).createChart(any(ChartConfig.class));
        
        // Verify the image can be read
        assertDoesNotThrow(() -> {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(result));
            assertNotNull(image);
        });
    }
    
    @Test
    void testCreateChartGridWithEmptySeriesList() throws Exception {
        // Create grid config
        ChartGrid.GridConfig gridConfig = ChartGrid.GridConfig.builder()
                .columns(2)
                .width(400)
                .height(300)
                .padding(10)
                .useMatplotlib(true)
                .baseConfig(createBaseConfig())
                .build();
        
        // Create chart grid with empty series list
        byte[] result = chartGrid.createChartGrid(new ArrayList<>(), gridConfig);
        
        // Verify the result is empty
        assertNotNull(result);
        assertEquals(0, result.length);
        
        // Verify that neither chart creator was called
        verify(matplotChartCreator, never()).createChart(any(ChartConfig.class));
        verify(complexChartCreator, never()).createChart(any(ChartConfig.class));
        verify(complexChartCreator, never()).exportChartAsPNG(any(), anyInt(), anyInt());
    }
    
    @Test
    void testCreateChartGridWithNullGridConfig() throws Exception {
        // Create chart grid with null grid config (should use defaults)
        byte[] result = chartGrid.createChartGrid(testSeriesList, null);
        
        // Verify the result
        assertNotNull(result);
        assertTrue(result.length > 0);
        
        // Verify that MatplotChartCreator was called for each series (default)
        verify(matplotChartCreator, times(testSeriesList.size())).createChart(any(ChartConfig.class));
    }
    
    @Test
    void testCreateChartGridWithDifferentColumnCount() throws Exception {
        // Test with 1 column
        ChartGrid.GridConfig gridConfig = ChartGrid.GridConfig.builder()
                .columns(1)
                .width(400)
                .height(300)
                .padding(10)
                .useMatplotlib(true)
                .baseConfig(createBaseConfig())
                .build();
        
        byte[] result = chartGrid.createChartGrid(testSeriesList, gridConfig);
        
        // Verify the result
        assertNotNull(result);
        assertTrue(result.length > 0);
        
        // Verify the image dimensions
        assertDoesNotThrow(() -> {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(result));
            assertNotNull(image);
            
            // Verify grid dimensions (1 column, 4 rows)
            int expectedWidth = 400; // 1 column * width
            int expectedHeight = 4 * 300 + 3 * 10; // 4 rows * height + 3 * padding
            assertEquals(expectedWidth, image.getWidth());
            assertEquals(expectedHeight, image.getHeight());
        });
    }
    
    // Helper methods
    
    private Bar createTestBar(ZonedDateTime dateTime, double open, double high, double low, double close, double volume) {
        return new BaseBar(
                Duration.ofDays(1),
                dateTime,
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                BigDecimal.valueOf(volume)
        );
    }
    
    private ChartConfig createBaseConfig() {
        ChartConfig config = new ChartConfig();
        config.setChartType(ChartType.CANDLESTICK);
        config.setShowVolume(true);
        config.setShowLegend(true);
        return config;
    }
    
    private byte[] createTestImageData() {
        try {
            // Create a simple test image
            BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics g = image.getGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, 100, 100);
            g.setColor(java.awt.Color.BLACK);
            g.drawRect(10, 10, 80, 80);
            g.dispose();
            
            // Convert to byte array
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}