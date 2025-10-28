import React, { useEffect, useRef, useState } from "react";
import { type CandlestickData, createChart, type IChartApi } from "lightweight-charts";
import { getAllPlugins } from "./plugins/PluginRegistry";
import { fetchOHLC, loadOverlaysFromServer } from "./proApi";
import { buildFirstOfDaySet, formatTickMarkIST, formatCrosshairISTFull } from "./timeUtils";

type BarRow = {
  time?: number;
  timestamp?: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

type ChartPanel = {
  timeframe: string;
  containerRef: React.RefObject<HTMLDivElement>;
  chartRef: React.MutableRefObject<IChartApi | null>;
  pluginMapRef: React.MutableRefObject<Record<string, any>>;
};

type Props = {
  symbol: string;
  timeframes: string[];
  mapping: Record<string, string>;
};

export default function MultiPanelChart({ symbol, timeframes, mapping }: Props) {
  const [panels] = useState<ChartPanel[]>(() =>
    timeframes.map((tf) => ({
      timeframe: tf,
      containerRef: React.createRef<HTMLDivElement>(),
      chartRef: { current: null },
      pluginMapRef: { current: {} },
    }))
  );

  const toEnum = (p: string) => mapping[p] ?? p;

  useEffect(() => {
    const initPanel = async (panel: ChartPanel) => {
      if (!panel.containerRef.current) return;

      const container = panel.containerRef.current;
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
            const firstBarOfDayMs = new Set<number>();
            return formatTickMarkIST(time as number, panel.timeframe, firstBarOfDayMs);
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

      panel.chartRef.current = chart;

      const series = chart.addCandlestickSeries({
        upColor: "#26a69a",
        downColor: "#ef5350",
        wickUpColor: "#26a69a",
        wickDownColor: "#ef5350",
        borderVisible: false,
      });

      // Load candles
      try {
        const rows: BarRow[] = await fetchOHLC(symbol, toEnum(panel.timeframe));
        const data: CandlestickData[] = rows.map((b) => ({
          time: (b.time ?? b.timestamp ?? 0) as number,
          open: b.open,
          high: b.high,
          low: b.low,
          close: b.close,
        }));
        series.setData(data);
      } catch (e) {
        console.error("Failed to load candles for", panel.timeframe, e);
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
      panel.pluginMapRef.current = instances;

      // Load overlays from server
      try {
        const ok = await loadOverlaysFromServer(symbol, panel.timeframe);
        if (ok?.overlays) {
          for (const def of getAllPlugins()) {
            const data = ok.overlays[def.key] ?? [];
            try {
              instances[def.key]?.importAll?.(data);
            } catch (e) {
              console.warn("Import overlay failed for", def.key, e);
            }
          }
        }
      } catch (e) {
        console.warn("Load overlays failed for", panel.timeframe, e);
      }
    };

    // Initialize all panels
    panels.forEach(initPanel);

    // Handle resize
    const onResize = () => {
      panels.forEach((panel) => {
        if (!panel.chartRef.current || !panel.containerRef.current) return;
        const rect = panel.containerRef.current.getBoundingClientRect();
        panel.chartRef.current.applyOptions({
          width: Math.max(200, Math.floor(rect.width)),
          height: Math.max(200, Math.floor(rect.height)),
        });
      });
    };
    window.addEventListener("resize", onResize);

    return () => {
      window.removeEventListener("resize", onResize);
      panels.forEach((panel) => {
        try {
          const instances = panel.pluginMapRef.current;
          for (const key of Object.keys(instances)) {
            instances[key]?.destroy?.();
          }
        } catch {}
        try {
          panel.chartRef.current?.remove?.();
        } catch {}
        panel.chartRef.current = null;
        panel.pluginMapRef.current = {};
      });
    };
  }, [symbol, timeframes, panels, mapping, toEnum]);

  // Calculate grid layout based on number of panels
  const getGridLayout = () => {
    const count = panels.length;
    if (count === 1) return { cols: 1, rows: 1 };
    if (count === 2) return { cols: 2, rows: 1 };
    if (count === 3) return { cols: 3, rows: 1 };
    if (count === 4) return { cols: 2, rows: 2 };
    if (count <= 6) return { cols: 3, rows: 2 };
    return { cols: 3, rows: 3 };
  };

  const layout = getGridLayout();

  return (
    <div style={{ width: "100%", height: "100vh", display: "flex", flexDirection: "column" }}>
      {/* Header */}
      <div
        style={{
          padding: "12px 16px",
          background: "rgba(255,255,255,0.95)",
          borderBottom: "1px solid #e0e0e0",
          display: "flex",
          alignItems: "center",
          gap: 12,
        }}
      >
        <span style={{ fontWeight: 600, fontSize: 16, color: "#1976d2" }}>{symbol}</span>
        <span style={{ color: "#666" }}>•</span>
        <span style={{ color: "#666" }}>{timeframes.join(", ")}</span>
      </div>

      {/* Grid of charts */}
      <div
        style={{
          flex: 1,
          display: "grid",
          gridTemplateColumns: `repeat(${layout.cols}, 1fr)`,
          gridTemplateRows: `repeat(${layout.rows}, 1fr)`,
          gap: 8,
          padding: 8,
          background: "#f5f5f5",
        }}
      >
        {panels.map((panel) => (
          <div
            key={panel.timeframe}
            style={{
              position: "relative",
              background: "#fff",
              borderRadius: 8,
              overflow: "hidden",
              boxShadow: "0 2px 8px rgba(0,0,0,0.08)",
            }}
          >
            {/* Timeframe label */}
            <div
              style={{
                position: "absolute",
                top: 8,
                left: 8,
                zIndex: 10,
                background: "rgba(255,255,255,0.9)",
                padding: "4px 8px",
                borderRadius: 4,
                fontSize: 12,
                fontWeight: 600,
                color: "#1976d2",
                boxShadow: "0 1px 3px rgba(0,0,0,0.1)",
              }}
            >
              {panel.timeframe}
            </div>
            <div ref={panel.containerRef} style={{ width: "100%", height: "100%" }} />
          </div>
        ))}
      </div>
    </div>
  );
}
