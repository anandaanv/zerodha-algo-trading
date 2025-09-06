import React, { useEffect, useMemo, useRef, useState } from "react";
import { init, dispose, type KLineData, type Chart } from "klinecharts";
import { fetchIntervalMapping, fetchOhlc } from "../api";
import OverlayToolbar from "./OverlayToolbar";
import type { OhlcBar, Timeframe } from "../types";

const defaultIntervalMap: Record<string, string> = {
  "1m": "OneMinute",
  "3m": "ThreeMinute",
  "5m": "FiveMinute",
  "15m": "FifteenMinute",
  "30m": "ThirtyMinute",
  "1h": "OneHour",
  "4h": "FourHours",
  "1d": "Day",
  "1w": "Week",
};

export default function KLineApp() {
  const [symbol, setSymbol] = useState("TCS");
  const [timeframe, setTimeframe] = useState<Timeframe>("1h");
  const [intervalMap, setIntervalMap] = useState<Record<string, string>>(defaultIntervalMap);
  const [bars, setBars] = useState<OhlcBar[]>([]);
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<Chart | null>(null);

  const width = 1200;
  const height = 640;

  // init / dispose chart
  useEffect(() => {
    if (!containerRef.current) return;
    const chart = init(containerRef.current, { styles: tvLikeStyles });
    chartRef.current = chart;

    const onResize = () => {
      if (!containerRef.current || !chartRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      chartRef.current.setSize(rect.width, rect.height);
    };
    window.addEventListener("resize", onResize);

    // Keyboard overlays using built-in API (createOverlay / removeAllOverlay). No custom toolbar.
    const onKey = (e: KeyboardEvent) => {
      const c: any = chartRef.current as any;
      if (!c) return;
      const key = e.key.toLowerCase();
      const withShift = e.shiftKey;

      // Clear all overlays: Shift + C
      if (withShift && key === "c") {
        if (typeof c.removeAllOverlay === "function") c.removeAllOverlay();
        else if (typeof c.removeAllGraphicMark === "function") c.removeAllGraphicMark();
        e.preventDefault();
        return;
      }

      // Map keys to built-in overlays (fallback to graphic mark ids on older versions)
      const map: Record<string, string> = {
        l: "straightLine",
        y: "rayLine",
        s: "segment",
        p: "parallelStraightLine",
        h: "horizontalStraightLine",
        v: "verticalStraightLine",
        r: "rect",
        c: "circle",
        t: "triangle",
        d: "diamond",
        f: "fibonacciRetracement",
        e: "fibonacciExtension",
      };
      const overlayId = map[key];
      if (overlayId) {
        try {
          if (typeof c.createOverlay === "function") c.createOverlay(overlayId);
          else if (typeof c.createGraphicMark === "function") c.createGraphicMark(overlayId);
          e.preventDefault();
        } catch (err) {
          console.warn("Overlay not available:", overlayId, err);
        }
      }
    };
    window.addEventListener("keydown", onKey);

    return () => {
      window.removeEventListener("resize", onResize);
      window.removeEventListener("keydown", onKey);
      dispose(chart);
      chartRef.current = null;
    };
  }, []);

  // interval mapping
  useEffect(() => {
    fetchIntervalMapping()
      .then((map) => {
        if (map && Object.keys(map).length > 0) {
          setIntervalMap(map);
          const keys = Object.keys(map) as Timeframe[];
          if (!keys.includes(timeframe)) {
            setTimeframe(keys.includes("1h") ? "1h" : (keys[0] as Timeframe));
          }
        }
      })
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function load() {
    const intervalParam = intervalMap[timeframe] ?? timeframe;
    const data = await fetchOhlc(symbol, intervalParam);
    setBars(data);
    const klines: KLineData[] = data.map((b) => ({
      timestamp: b.time * 1000,
      open: b.open,
      high: b.high,
      low: b.low,
      close: b.close,
      volume: b.volume,
    }));
    chartRef.current?.applyNewData(klines);
  }

  useEffect(() => {
    // auto-load when chart is ready
    if (chartRef.current) load().catch(console.error);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chartRef.current]);

  const timeframeOptions = useMemo(() => {
    const order = ["1m", "3m", "5m", "15m", "30m", "1h", "4h", "1d", "1w"];
    const keys = Object.keys(intervalMap);
    return order.filter((k) => keys.includes(k)).concat(keys.filter((k) => !order.includes(k)));
  }, [intervalMap]);

  // Helpers to create/clear overlays (falls back to v9 graphic marks if needed)
  function createOverlay(id: string) {
    const c: any = chartRef.current as any;
    if (!c) return;
    if (typeof c.createOverlay === "function") c.createOverlay(id);
    else if (typeof c.createGraphicMark === "function") c.createGraphicMark(id);
  }
  function clearOverlays() {
    const c: any = chartRef.current as any;
    if (!c) return;
    if (typeof c.removeAllOverlay === "function") c.removeAllOverlay();
    else if (typeof c.removeAllGraphicMark === "function") c.removeAllGraphicMark();
  }

  return (
    <div className="container" style={{ height: "100%", display: "grid", gridTemplateRows: "auto 1fr" }}>
      <div className="panel" style={{ display: "flex", gap: 8, alignItems: "center", padding: 8 }}>
        <strong>Symbol</strong>
        <input value={symbol} onChange={(e) => setSymbol(e.target.value)} placeholder="e.g., TCS" />
        <strong>Timeframe</strong>
        <select value={timeframe} onChange={(e) => setTimeframe(e.target.value as Timeframe)}>
          {timeframeOptions.map((k) => (
            <option key={k} value={k}>
              {k}
            </option>
          ))}
        </select>
        <button onClick={load}>Load</button>
        <div style={{ flex: 1 }} />
        <div style={{ fontSize: 12, color: "#555" }}>
          Use the overlay toolbar on the chart (top-left) to draw. Shortcuts: L/Y/S/P/H/V/R/C/T/D/F/E · Shift+C clears.
        </div>
      </div>

      <div style={{ position: "relative", width, height, borderTop: "1px solid #e0e0e0" }}>
        <div ref={containerRef} style={{ position: "absolute", inset: 0 }} />
        <OverlayToolbar onCreate={createOverlay} onClear={clearOverlays} />
      </div>
    </div>
  );
}

const tvLikeStyles = {
  candle: {
    type: "candle_solid",
    priceMark: { last: { upColor: "#26a69a", downColor: "#ef5350" } },
    upColor: "#26a69a",
    upBorderColor: "#26a69a",
    upWickColor: "#26a69a",
    downColor: "#ef5350",
    downBorderColor: "#ef5350",
    downWickColor: "#ef5350",
  },
  grid: {
    horizontal: { color: "#f0f0f0", size: 1, style: "dashed" },
    vertical: { color: "#f0f0f0", size: 1, style: "dashed" },
  },
  crosshair: {
    horizontal: { color: "#9e9e9e" },
    vertical: { color: "#9e9e9e" },
  },
  yAxis: {
    inside: false,
    tickText: { color: "#555" },
    border: { color: "#e0e0e0" },
  },
  xAxis: {
    tickText: { color: "#555" },
    border: { color: "#e0e0e0" },
  },
};
