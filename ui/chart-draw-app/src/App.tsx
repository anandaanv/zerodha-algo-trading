import React, { useEffect, useMemo, useRef, useState } from "react";
import { createChart, ISeriesApi, LineStyle } from "lightweight-charts";
import { createDrawing, deleteDrawing, fetchIntervalMapping, fetchOhlc, listDrawings } from "./api";
import DrawLayer from "./draw/DrawLayer";
import type { Drawing, DrawingType, OhlcBar, Timeframe, Tool } from "./types";

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

export default function App() {
  const [symbol, setSymbol] = useState("AAPL");
  const [timeframe, setTimeframe] = useState<Timeframe>("1h");
  const [intervalMap, setIntervalMap] = useState<Record<string, string>>(defaultIntervalMap);
  const [tool, setTool] = useState<Tool>({ kind: "cursor" });
  const [bars, setBars] = useState<OhlcBar[]>([]);
  const [draws, setDraws] = useState<Drawing[]>([]);
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<ReturnType<typeof createChart>>();
  const seriesRef = useRef<ISeriesApi<"Candlestick">>();

  const width = 1200;
  const height = 640;

  useEffect(() => {
    if (!containerRef.current) return;
    const chart = createChart(containerRef.current, {
      width, height, layout: { background: { color: "#fff" }, textColor: "#333" },
      rightPriceScale: { borderColor: "#e0e0e0" },
      timeScale: { borderColor: "#e0e0e0" }
    });
    const series = chart.addCandlestickSeries({
      upColor: "#26a69a", borderUpColor: "#26a69a", wickUpColor: "#26a69a",
      downColor: "#ef5350", borderDownColor: "#ef5350", wickDownColor: "#ef5350",
      borderVisible: false
    });
    chartRef.current = chart;
    seriesRef.current = series;
    return () => chart.remove();
  }, []);

  // Load interval mapping on startup (with graceful fallback)
  useEffect(() => {
    fetchIntervalMapping()
      .then(map => {
        if (map && Object.keys(map).length > 0) {
          setIntervalMap(map);
          // ensure current selection is valid
          const keys = Object.keys(map) as Timeframe[];
          if (!keys.includes(timeframe)) {
            setTimeframe((keys.includes("1h") ? "1h" : (keys[0] as Timeframe)));
          }
        }
      })
      .catch(() => {
        // keep defaultIntervalMap on failure
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function load() {
    const intervalParam = intervalMap[timeframe] ?? timeframe; // translate uiKey -> enumName
    const data = await fetchOhlc(symbol, intervalParam);
    setBars(data);
    seriesRef.current?.setData(data.map(b => ({
      time: b.time as any,
      open: b.open, high: b.high, low: b.low, close: b.close
    })));
    // For drawings, pass same translated value if backend expects enum; adjust if your API wants uiKey
    const ds = await listDrawings(symbol, intervalParam);
    setDraws(ds);
  }

  useEffect(() => {
    // auto-load on initial render
    if (chartRef.current) load().catch(console.error);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chartRef.current]);

  const existing = useMemo(() => {
    return draws.map(d => ({ type: d.type, payload: JSON.parse(d.payloadJson) }));
  }, [draws]);

  async function handleComplete(type: DrawingType, payload: any) {
    const saved = await createDrawing({
      symbol,
      timeframe, // stored as selected uiKey for user context; adjust if you prefer enumName
      type,
      payloadJson: JSON.stringify(payload),
      name: `${type} ${new Date().toISOString()}`
    } as Drawing);
    setDraws([saved, ...draws]);
  }

  const timeframeOptions = useMemo(() => {
    // Attempt a sensible ordering
    const order = ["1m", "3m", "5m", "15m", "30m", "1h", "4h", "1d", "1w"];
    const keys = Object.keys(intervalMap);
    return order.filter(k => keys.includes(k)).concat(keys.filter(k => !order.includes(k)));
  }, [intervalMap]);

  return (
    <div className="container">
      <div className="panel">
        <strong>Symbol</strong>
        <input value={symbol} onChange={e => setSymbol(e.target.value)} placeholder="e.g., AAPL" />
        <strong>Timeframe</strong>
        <select value={timeframe} onChange={e => setTimeframe(e.target.value as Timeframe)}>
          {timeframeOptions.map((k) => (
            <option key={k} value={k}>{k}</option>
          ))}
        </select>
        <button onClick={load}>Load</button>
        <div style={{ flex: 1 }} />
        <div className="toolbar">
          <button className={tool.kind === "cursor" ? "active" : ""} onClick={() => setTool({ kind: "cursor" })}>Cursor</button>
          <button className={tool.kind === "line" ? "active" : ""} onClick={() => setTool({ kind: "line" })}>Line</button>
          <button className={tool.kind === "channel" ? "active" : ""} onClick={() => setTool({ kind: "channel" })}>Channel</button>
          <button className={tool.kind === "triangle" ? "active" : ""} onClick={() => setTool({ kind: "triangle" })}>Triangle</button>
          <button className={tool.kind === "fib-retracement" ? "active" : ""} onClick={() => setTool({ kind: "fib-retracement" })}>Fib Retracement</button>
          <button className={tool.kind === "fib-extension" ? "active" : ""} onClick={() => setTool({ kind: "fib-extension" })}>Fib Extension</button>
        </div>
      </div>

      <div className="chart-wrap">
        <div ref={containerRef} style={{ width, height }} />
        <div className="overlay">
          <DrawLayer
            width={width}
            height={height}
            tool={tool}
            existing={existing}
            onComplete={handleComplete}
          />
        </div>
      </div>
    </div>
  );
}
