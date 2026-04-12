import React, { useEffect, useRef } from "react";
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  type SeriesMarker,
  type Time,
  type CandlestickData,
} from "lightweight-charts";
import { fetchOHLC } from "../legacy-chart/proApi";
import { PatternDto } from "../api";

interface PatternChartPanelProps {
  symbol: string;
  timeframe: string;
  mapping: Record<string, string>;
  selectedPattern: PatternDto | null;
}

export default function PatternChartPanel({
  symbol,
  timeframe,
  mapping,
  selectedPattern,
}: PatternChartPanelProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const trendlineSeriesRef = useRef<ISeriesApi<"Line">[]>([]);

  // Load chart and candles when symbol/timeframe change
  useEffect(() => {
    if (!containerRef.current) return;

    let resizeObserver: ResizeObserver | null = null;
    let resizeHandler: (() => void) | null = null;

    const toEnum = (p: string) => mapping[p] ?? p;

    const init = async () => {
      const container = containerRef.current!;
      const bounds = container.getBoundingClientRect();

      // Create chart
      const chart = createChart(container, {
        width: Math.max(200, Math.floor(bounds.width)),
        height: Math.max(200, Math.floor(bounds.height)),
        layout: {
          background: { color: "#131722" },
          textColor: "#d1d4dc",
        },
        grid: {
          vertLines: { color: "#2a2a2a" },
          horzLines: { color: "#2a2a2a" },
        },
        timeScale: {
          timeVisible: true,
          secondsVisible: false,
        },
        handleScroll: {
          mouseWheel: true,
          pressedMouseMove: true,
          horzTouchDrag: true,
          vertTouchDrag: true,
        },
      });

      chartRef.current = chart;

      const series = chart.addCandlestickSeries({
        upColor: "#26a69a",
        downColor: "#ef5350",
        wickUpColor: "#26a69a",
        wickDownColor: "#ef5350",
        borderVisible: false,
      });

      seriesRef.current = series;

      // Load candles
      try {
        const rows = await fetchOHLC(symbol, toEnum(timeframe));
        const data: CandlestickData[] = rows.map((b) => ({
          time: (b.time ?? b.timestamp ?? 0) as number,
          open: b.open,
          high: b.high,
          low: b.low,
          close: b.close,
        }));

        series.setData(data);
      } catch (e) {
        console.error("Failed to load candles for", timeframe, e);
      }

      // Handle resize
      resizeHandler = () => {
        if (!chartRef.current || !containerRef.current) return;
        const rect = containerRef.current.getBoundingClientRect();
        chartRef.current.applyOptions({
          width: Math.max(200, Math.floor(rect.width)),
          height: Math.max(200, Math.floor(rect.height)),
        });
      };
      window.addEventListener("resize", resizeHandler);

      resizeObserver = new ResizeObserver(() => {
        resizeHandler?.();
      });
      resizeObserver.observe(container);

      setTimeout(() => resizeHandler?.(), 100);
    };

    init();

    return () => {
      if (resizeObserver) {
        resizeObserver.disconnect();
      }
      if (resizeHandler) {
        window.removeEventListener("resize", resizeHandler);
      }

      trendlineSeriesRef.current = [];

      try {
        chartRef.current?.remove?.();
      } catch {}

      chartRef.current = null;
      seriesRef.current = null;
    };
  }, [symbol, timeframe, mapping]);

  // Draw trendlines + marker when selectedPattern changes
  useEffect(() => {
    if (!chartRef.current || !seriesRef.current) return;

    // Remove old trendline series
    trendlineSeriesRef.current.forEach((s) => {
      try { chartRef.current?.removeSeries(s); } catch {}
    });
    trendlineSeriesRef.current = [];

    if (selectedPattern === null) {
      seriesRef.current.setMarkers([]);
      return;
    }

    // Marker at entry bar
    const marker: SeriesMarker<Time> = {
      time: Math.floor(new Date(selectedPattern.keyLevelTime).getTime() / 1000) as Time,
      position: selectedPattern.bullish ? "belowBar" : "aboveBar",
      color: selectedPattern.bullish ? "#4caf50" : "#f44336",
      shape: selectedPattern.bullish ? "arrowUp" : "arrowDown",
      text: selectedPattern.patternType.replace(/_/g, " "),
    };
    seriesRef.current.setMarkers([marker]);

    // Compute trendline time range
    const t0 = Math.floor(new Date(selectedPattern.p0Time).getTime() / 1000);
    const t1 = Math.floor(new Date(selectedPattern.keyLevelTime).getTime() / 1000);
    const patternWidth = Math.max(t1 - t0, 3600); // at least 1h
    const tEnd = (t1 + patternWidth) as Time;
    const tStart = t0 as Time;

    const sl = selectedPattern.bullish
      ? selectedPattern.keyLevel - 2 * selectedPattern.atr
      : selectedPattern.keyLevel + 2 * selectedPattern.atr;

    const lines: Array<{ price: number; color: string; label: string }> = [
      { price: selectedPattern.keyLevel, color: "#29b6f6", label: "Entry" },
      { price: selectedPattern.target,   color: "#ffb300", label: "Target" },
      { price: sl,                        color: "#ef5350", label: "SL" },
    ];

    lines.forEach(({ price, color }) => {
      const s = chartRef.current!.addLineSeries({
        color,
        lineWidth: 1,
        lineStyle: 2, // dashed
        priceLineVisible: false,
        lastValueVisible: false,
        crosshairMarkerVisible: false,
      });
      s.setData([
        { time: tStart, value: price },
        { time: tEnd,   value: price },
      ]);
      trendlineSeriesRef.current.push(s);
    });
  }, [selectedPattern]);

  return (
    <div
      ref={containerRef}
      style={{
        width: "100%",
        height: "100%",
      }}
    />
  );
}
