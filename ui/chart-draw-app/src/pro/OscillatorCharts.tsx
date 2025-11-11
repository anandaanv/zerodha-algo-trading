import React, { useEffect, useRef } from "react";
import { type IChartApi, createChart, type ISeriesApi } from "lightweight-charts";
import {
  calculateRSI,
  calculateMACD,
  calculateStochastic,
  calculateStochasticRSI,
  calculateADX,
} from "./indicators";

type BarRow = {
  time?: number;
  timestamp?: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

interface OscillatorChartsProps {
  data: Array<{ time: number; [key: string]: any }>;
  rows: BarRow[];
  mainChartRef: React.RefObject<IChartApi | null>;
  layout: "side" | "bottom";
  mode: "scrollable" | "compact";
}

export default function OscillatorCharts({ data, rows, mainChartRef, layout, mode }: OscillatorChartsProps) {
  const rsiContainerRef = useRef<HTMLDivElement>(null);
  const macdContainerRef = useRef<HTMLDivElement>(null);
  const stochContainerRef = useRef<HTMLDivElement>(null);
  const stochRSIContainerRef = useRef<HTMLDivElement>(null);
  const adxContainerRef = useRef<HTMLDivElement>(null);

  const rsiChartRef = useRef<IChartApi | null>(null);
  const macdChartRef = useRef<IChartApi | null>(null);
  const stochChartRef = useRef<IChartApi | null>(null);
  const stochRSIChartRef = useRef<IChartApi | null>(null);
  const adxChartRef = useRef<IChartApi | null>(null);

  useEffect(() => {
    if (!rsiContainerRef.current || !macdContainerRef.current ||
        !stochContainerRef.current || !stochRSIContainerRef.current ||
        !adxContainerRef.current) return;

    const closes = rows.map(r => r.close);
    const highs = rows.map(r => r.high);
    const lows = rows.map(r => r.low);
    const times = data.map(d => d.time);

    // Calculate chart height based on mode
    // In compact mode, divide available space among 5 charts
    // Assume parent container height and calculate proportionally
    const chartHeight = mode === "compact" ? 100 : 150;

    const chartOptions = {
      width: rsiContainerRef.current.clientWidth,
      height: chartHeight,
      layout: {
        background: { color: "#ffffff" },
        textColor: "#333",
      },
      grid: {
        vertLines: { color: "#f0f0f0" },
        horzLines: { color: "#f0f0f0" },
      },
      timeScale: {
        timeVisible: true,
        secondsVisible: false,
      },
    };

    // 1. RSI Chart
    const rsiChart = createChart(rsiContainerRef.current, chartOptions);
    rsiChartRef.current = rsiChart;

    const rsi = calculateRSI(closes, 14);
    const rsiSeries = rsiChart.addLineSeries({
      color: "#9B59B6",
      lineWidth: 2,
      title: "RSI(14)",
    });
    rsiSeries.setData(
      times.map((t, i) => ({ time: t, value: rsi[i] })).filter(d => !isNaN(d.value))
    );

    // RSI horizontal reference lines
    rsiSeries.createPriceLine({
      price: 70,
      color: "#E74C3C",
      lineWidth: 1,
      lineStyle: 2,
      axisLabelVisible: true,
      title: "Overbought",
    });
    rsiSeries.createPriceLine({
      price: 60,
      color: "#F39C12",
      lineWidth: 1,
      lineStyle: 2,
      axisLabelVisible: true,
    });
    rsiSeries.createPriceLine({
      price: 40,
      color: "#F39C12",
      lineWidth: 1,
      lineStyle: 2,
      axisLabelVisible: true,
    });
    rsiSeries.createPriceLine({
      price: 30,
      color: "#E74C3C",
      lineWidth: 1,
      lineStyle: 2,
      axisLabelVisible: true,
      title: "Oversold",
    });

    // 2. MACD Chart
    const macdChart = createChart(macdContainerRef.current, chartOptions);
    macdChartRef.current = macdChart;

    const macd = calculateMACD(closes, 12, 26, 9);
    const macdLineSeries = macdChart.addLineSeries({
      color: "#3498DB",
      lineWidth: 2,
      title: "MACD",
    });
    const macdSignalSeries = macdChart.addLineSeries({
      color: "#E74C3C",
      lineWidth: 2,
      title: "Signal",
    });
    const macdHistogramSeries = macdChart.addHistogramSeries({
      color: "#26a69a",
      priceFormat: { type: "price", precision: 4, minMove: 0.0001 },
    });

    macdLineSeries.setData(
      times.map((t, i) => ({ time: t, value: macd.macdLine[i] })).filter(d => !isNaN(d.value))
    );
    macdSignalSeries.setData(
      times.map((t, i) => ({ time: t, value: macd.signalLine[i] })).filter(d => !isNaN(d.value))
    );
    macdHistogramSeries.setData(
      times
        .map((t, i) => ({
          time: t,
          value: macd.histogram[i],
          color: macd.histogram[i] >= 0 ? "#26a69a" : "#ef5350",
        }))
        .filter(d => !isNaN(d.value))
    );

    // 3. Stochastic Chart
    const stochChart = createChart(stochContainerRef.current, chartOptions);
    stochChartRef.current = stochChart;

    const stoch = calculateStochastic(highs, lows, closes, 14, 3, 3);
    const stochKSeries = stochChart.addLineSeries({
      color: "#1ABC9C",
      lineWidth: 2,
      title: "%K",
    });
    const stochDSeries = stochChart.addLineSeries({
      color: "#E67E22",
      lineWidth: 2,
      title: "%D",
    });

    stochKSeries.setData(
      times.map((t, i) => ({ time: t, value: stoch.k[i] })).filter(d => !isNaN(d.value))
    );
    stochDSeries.setData(
      times.map((t, i) => ({ time: t, value: stoch.d[i] })).filter(d => !isNaN(d.value))
    );

    // Stochastic horizontal lines
    stochKSeries.createPriceLine({
      price: 80,
      color: "#E74C3C",
      lineWidth: 1,
      lineStyle: 2,
      axisLabelVisible: true,
    });
    stochKSeries.createPriceLine({
      price: 20,
      color: "#E74C3C",
      lineWidth: 1,
      lineStyle: 2,
      axisLabelVisible: true,
    });

    // 4. Stochastic RSI Chart
    const stochRSIChart = createChart(stochRSIContainerRef.current, chartOptions);
    stochRSIChartRef.current = stochRSIChart;

    const stochRSI = calculateStochasticRSI(rsi, 14, 3, 3);
    const stochRSIKSeries = stochRSIChart.addLineSeries({
      color: "#16A085",
      lineWidth: 2,
      title: "StochRSI %K",
    });
    const stochRSIDSeries = stochRSIChart.addLineSeries({
      color: "#D35400",
      lineWidth: 2,
      title: "StochRSI %D",
    });

    stochRSIKSeries.setData(
      times.map((t, i) => ({ time: t, value: stochRSI.k[i] })).filter(d => !isNaN(d.value))
    );
    stochRSIDSeries.setData(
      times.map((t, i) => ({ time: t, value: stochRSI.d[i] })).filter(d => !isNaN(d.value))
    );

    // StochRSI horizontal lines
    stochRSIKSeries.createPriceLine({
      price: 80,
      color: "#E74C3C",
      lineWidth: 1,
      lineStyle: 2,
      axisLabelVisible: true,
    });
    stochRSIKSeries.createPriceLine({
      price: 20,
      color: "#E74C3C",
      lineWidth: 1,
      lineStyle: 2,
      axisLabelVisible: true,
    });

    // 5. ADX Chart
    const adxChart = createChart(adxContainerRef.current, chartOptions);
    adxChartRef.current = adxChart;

    const adx = calculateADX(highs, lows, closes, 14);
    const adxSeries = adxChart.addLineSeries({
      color: "#8E44AD",
      lineWidth: 2,
      title: "ADX(14)",
    });

    adxSeries.setData(
      times.map((t, i) => ({ time: t, value: adx[i] })).filter(d => !isNaN(d.value))
    );

    // ADX reference line at 25 (strong trend threshold)
    adxSeries.createPriceLine({
      price: 25,
      color: "#95a5a6",
      lineWidth: 1,
      lineStyle: 2,
      axisLabelVisible: true,
      title: "Strong Trend",
    });

    // Synchronize crosshair with main chart when layout is side
    if (layout === "side" && mainChartRef.current) {
      const charts = [rsiChart, macdChart, stochChart, stochRSIChart, adxChart];
      const mainChart = mainChartRef.current;

      // Sync oscillator charts to main chart
      charts.forEach((chart) => {
        chart.subscribeCrosshairMove((param) => {
          if (param.time) {
            mainChart.timeScale().scrollToPosition(0, false);
            // Trigger crosshair update on main chart
            const coordinate = mainChart.timeScale().timeToCoordinate(param.time as any);
            if (coordinate) {
              // This will sync the time axis
              mainChart.timeScale().scrollToPosition(
                (mainChart.timeScale().scrollPosition() || 0),
                false
              );
            }
          }
        });
      });

      // Sync main chart to oscillator charts
      mainChart.subscribeCrosshairMove((param) => {
        if (param.time) {
          charts.forEach((chart) => {
            chart.timeScale().scrollToRealTime();
          });
        }
      });
    }

    // Cleanup
    return () => {
      rsiChart?.remove();
      macdChart?.remove();
      stochChart?.remove();
      stochRSIChart?.remove();
      adxChart?.remove();
    };
  }, [data, rows, mainChartRef, layout, mode]);

  const chartContainerHeight = mode === "compact" ? 100 : 150;
  const titleFontSize = mode === "compact" ? 10 : 12;
  const gap = mode === "compact" ? 4 : 8;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap, height: "100%", justifyContent: mode === "compact" ? "space-evenly" : "flex-start" }}>
      <div style={{ flex: mode === "compact" ? 1 : "0 0 auto", display: "flex", flexDirection: "column" }}>
        <div style={{ fontSize: titleFontSize, fontWeight: 600, padding: "2px 8px", color: "#666" }}>
          RSI (14)
        </div>
        <div ref={rsiContainerRef} style={{ height: chartContainerHeight, flex: 1 }} />
      </div>

      <div style={{ flex: mode === "compact" ? 1 : "0 0 auto", display: "flex", flexDirection: "column" }}>
        <div style={{ fontSize: titleFontSize, fontWeight: 600, padding: "2px 8px", color: "#666" }}>
          MACD (12, 26, 9)
        </div>
        <div ref={macdContainerRef} style={{ height: chartContainerHeight, flex: 1 }} />
      </div>

      <div style={{ flex: mode === "compact" ? 1 : "0 0 auto", display: "flex", flexDirection: "column" }}>
        <div style={{ fontSize: titleFontSize, fontWeight: 600, padding: "2px 8px", color: "#666" }}>
          Stochastic (14, 3, 3)
        </div>
        <div ref={stochContainerRef} style={{ height: chartContainerHeight, flex: 1 }} />
      </div>

      <div style={{ flex: mode === "compact" ? 1 : "0 0 auto", display: "flex", flexDirection: "column" }}>
        <div style={{ fontSize: titleFontSize, fontWeight: 600, padding: "2px 8px", color: "#666" }}>
          Stochastic RSI (14, 3, 3)
        </div>
        <div ref={stochRSIContainerRef} style={{ height: chartContainerHeight, flex: 1 }} />
      </div>

      <div style={{ flex: mode === "compact" ? 1 : "0 0 auto", display: "flex", flexDirection: "column" }}>
        <div style={{ fontSize: titleFontSize, fontWeight: 600, padding: "2px 8px", color: "#666" }}>
          ADX (14)
        </div>
        <div ref={adxContainerRef} style={{ height: chartContainerHeight, flex: 1 }} />
      </div>
    </div>
  );
}
