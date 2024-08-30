package com.dtech.chart;

import com.dtech.ta.patterns.FlagPattern;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYLineAnnotation;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.chart.ui.ApplicationFrame;
import org.jfree.data.xy.DefaultHighLowDataset;
import org.jfree.data.xy.OHLCDataset;
import org.jfree.chart.ChartUtils;

import org.ta4j.core.BarSeries;

import java.awt.*;
import java.io.File;
import java.util.Date;
import java.util.List;

public class FlagVisualizer extends ApplicationFrame {

    private BarSeries series;
    private List<FlagPattern> flagPatterns;
    private JFreeChart chart;

    public FlagVisualizer(String title, BarSeries series, List<FlagPattern> flagPatterns) {
        super(title);
        this.series = series;
        this.flagPatterns = flagPatterns;

        OHLCDataset dataset = createDataset(series);
        this.chart = createChart(dataset);

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(new Dimension(800, 600));
        setContentPane(chartPanel);
    }

    private OHLCDataset createDataset(BarSeries series) {
        int itemCount = series.getBarCount();
        Date[] dates = new Date[itemCount];
        double[] highs = new double[itemCount];
        double[] lows = new double[itemCount];
        double[] opens = new double[itemCount];
        double[] closes = new double[itemCount];
        double[] volumes = new double[itemCount];

        for (int i = 0; i < itemCount; i++) {
            dates[i] = Date.from(series.getBar(i).getEndTime().toInstant());
            highs[i] = series.getBar(i).getHighPrice().doubleValue();
            lows[i] = series.getBar(i).getLowPrice().doubleValue();
            opens[i] = series.getBar(i).getOpenPrice().doubleValue();
            closes[i] = series.getBar(i).getClosePrice().doubleValue();
            volumes[i] = series.getBar(i).getVolume().doubleValue();
        }

        return new DefaultHighLowDataset(
                "Candlestick",
                dates,
                highs,
                lows,
                opens,
                closes,
                volumes
        );
    }

    private JFreeChart createChart(OHLCDataset dataset) {
        JFreeChart chart = ChartFactory.createCandlestickChart(
                "Candlestick Chart with Flags",
                "Time",
                "Price",
                dataset,
                false
        );

        XYPlot plot = (XYPlot) chart.getPlot();
        plot.setOrientation(PlotOrientation.VERTICAL);

        CandlestickRenderer renderer = new CandlestickRenderer();
        plot.setRenderer(renderer);

        // Draw flags
        drawFlags(plot);

        return chart;
    }

    private void drawFlags(XYPlot plot) {
        for (FlagPattern flag : flagPatterns) {
            int startIndex = flag.getStartIndex();
            int endIndex = flag.getEndIndex();

            double x1 = Date.from(series.getBar(startIndex).getEndTime().toInstant()).getTime();
            double x2 = Date.from(series.getBar(endIndex).getEndTime().toInstant()).getTime();

            double y1 = series.getBar(startIndex).getLowPrice().doubleValue();
            double y2 = series.getBar(endIndex).getHighPrice().doubleValue();

            XYLineAnnotation annotation = new XYLineAnnotation(x1, y1, x2, y2, new BasicStroke(2.0f), Color.RED);
            plot.addAnnotation(annotation);
        }
    }

    public void saveChartAsJPEG(String fileName) {
        try {
            ChartUtils.saveChartAsJPEG(new File(fileName+ ".jpg"), chart, 800, 600);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public JFreeChart getChart() {
        return chart;
    }
}
