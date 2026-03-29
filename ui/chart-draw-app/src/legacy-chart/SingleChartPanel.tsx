import React, { useEffect, useRef, useState } from "react";
import { type CandlestickData, createChart, type IChartApi, type ISeriesApi } from "lightweight-charts";
import { fetchOHLC, loadOverlaysFromServer } from "./proApi";
import { buildFirstOfDaySet, formatTickMarkIST, formatCrosshairISTFull } from "./timeUtils";
import { drawIndicators } from "./indicators";
import OscillatorCharts from "./OscillatorCharts";
import { getAllPlugins } from "./plugins/PluginRegistry";

type BarRow = {
  time?: number;
  timestamp?: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

interface SingleChartPanelProps {
  symbol: string;
  timeframe: string;
  mapping: Record<string, string>;
  showIndicators: boolean;
  indicatorMode?: "scrollable" | "compact";
  oscillatorLayout?: "side" | "bottom";
  overlaysOverride?: Record<string, any[]>;
  readonly?: boolean;
}

/**
 * Reusable single chart panel component that shows price chart and optionally indicators
 * Used by both ProApp (single view) and MultiPanelChart (grid view)
 */
export default function SingleChartPanel({
  symbol,
  timeframe,
  mapping,
  showIndicators,
  indicatorMode = "compact",
  oscillatorLayout = "bottom",
  overlaysOverride,
  readonly = false,
}: SingleChartPanelProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const indicatorSeriesRef = useRef<Record<string, ISeriesApi<any>>>({});
  const pluginMapRef = useRef<Record<string, any>>({});
  const firstBarOfDayMsRef = useRef<Set<number>>(new Set());
  const [oscillatorData, setOscillatorData] = useState<{ data: any[]; rows: BarRow[] } | null>(null);

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
          background: { color: "#ffffff" },
          textColor: "#333",
        },
        grid: {
          vertLines: { color: "#f0f0f0" },
          horzLines: { color: "#f0f0f0" },
        },
        timeScale: {
          timeVisible: true,
          secondsVisible: true,
          tickMarkFormatter: (time: number) => {
            return formatTickMarkIST(
              time as number,
              timeframe,
              firstBarOfDayMsRef.current
            );
          },
        },
        localization: {
          dateFormat: "dd MMM 'yy",
          timeFormatter: (time: any) => formatCrosshairISTFull(time),
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

      // Load candles
      try {
        const rows: BarRow[] = await fetchOHLC(symbol, toEnum(timeframe));
        const data: CandlestickData[] = rows.map((b) => ({
          time: (b.time ?? b.timestamp ?? 0) as number,
          open: b.open,
          high: b.high,
          low: b.low,
          close: b.close,
        }));

        firstBarOfDayMsRef.current = buildFirstOfDaySet(data.map((d) => d.time as number));
        series.setData(data);

        // Draw indicators if enabled
        if (showIndicators) {
          drawIndicators(chart, data, rows, indicatorSeriesRef, series);
          setOscillatorData({ data, rows });
        } else {
          setOscillatorData(null);
        }
      } catch (e) {
        console.error("Failed to load candles for", timeframe, e);
      }

      // Create plugins
      const instances: Record<string, any> = {};
      for (const def of getAllPlugins()) {
        try {
          instances[def.key] = new (def.ctor as any)({ chart, series, container });
        } catch (err) {
          console.error("Failed to init plugin", def.key, err);
        }
      }
      pluginMapRef.current = instances;

      // Load overlays
      try {
        const overlaySource = overlaysOverride ?? (await loadOverlaysFromServer(symbol, timeframe))?.overlays ?? {};
        for (const def of getAllPlugins()) {
          const data = overlaySource[def.key] ?? [];
          try {
            instances[def.key]?.importAll?.(data);
          } catch (e) {
            console.warn("Import overlay failed for", def.key, e);
          }
        }
      } catch (e) {
        console.warn("Load overlays failed for", timeframe, e);
      }

      // Disable drawing if readonly
      if (readonly) {
        for (const inst of Object.values(instances)) {
          const canvas = (inst as any)?.canvas as HTMLCanvasElement | undefined;
          if (canvas) canvas.style.pointerEvents = 'none';
        }
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

      try {
        const instances = pluginMapRef.current;
        for (const key of Object.keys(instances)) {
          instances[key]?.destroy?.();
        }
      } catch {}

      try {
        chartRef.current?.remove?.();
      } catch {}

      chartRef.current = null;
      pluginMapRef.current = {};
    };
  }, [symbol, timeframe, mapping, showIndicators]);

  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        display: "flex",
        flexDirection: oscillatorLayout === "side" ? "row" : "column",
      }}
    >
      {/* Main price chart */}
      <div
        style={{
          flex: oscillatorData
            ? indicatorMode === "compact"
              ? oscillatorLayout === "side"
                ? "0 0 50%"
                : "0 0 50%"
              : oscillatorLayout === "side"
              ? "0 0 65%"
              : "0 0 60%"
            : "1",
          position: "relative",
          minHeight: 0,
          overflow: "hidden",
        }}
      >
        <div ref={containerRef} style={{ width: "100%", height: "100%" }} />
      </div>

      {/* Oscillator charts panel */}
      {oscillatorData && (
        <div
          style={{
            flex:
              indicatorMode === "compact"
                ? oscillatorLayout === "side"
                  ? "0 0 50%"
                  : "0 0 50%"
                : oscillatorLayout === "side"
                ? "0 0 35%"
                : "0 0 40%",
            overflowY: indicatorMode === "compact" ? "hidden" : "auto",
            overflowX: "hidden",
            borderLeft: oscillatorLayout === "side" ? "1px solid #e0e0e0" : "none",
            borderTop: oscillatorLayout === "bottom" ? "1px solid #e0e0e0" : "none",
            background: "#fafafa",
            padding: 8,
            display: "flex",
            flexDirection: "column",
          }}
        >
          <OscillatorCharts
            data={oscillatorData.data}
            rows={oscillatorData.rows}
            mainChartRef={chartRef}
            layout={oscillatorLayout}
            mode={indicatorMode}
          />
        </div>
      )}
    </div>
  );
}
