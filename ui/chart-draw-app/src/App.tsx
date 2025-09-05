import React, { useEffect, useMemo, useRef, useState } from "react";
import { createChart, ISeriesApi } from "lightweight-charts";
import { createDrawing, deleteDrawing, fetchIntervalMapping, fetchOhlc, listDrawings, updateDrawing } from "./api";
import DrawLayer from "./draw/DrawLayer";
import ToolDrawer from "./ui/ToolDrawer";
import Legend from "./ui/Legend";
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
  const [symbol, setSymbol] = useState("TCS");
  const [timeframe, setTimeframe] = useState<Timeframe>("1h");
  const [intervalMap, setIntervalMap] = useState<Record<string, string>>(defaultIntervalMap);
  const [tool, setTool] = useState<Tool>({ kind: "cursor" });
  const [bars, setBars] = useState<OhlcBar[]>([]);
  const [draws, setDraws] = useState<Drawing[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [viewportVersion, setViewportVersion] = useState(0);
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<ReturnType<typeof createChart>>();
  const seriesRef = useRef<ISeriesApi<"Candlestick">>();

  const width = 1200;
  const height = 640;

  useEffect(() => {
    if (!containerRef.current) return;
    const chart = createChart(containerRef.current, {
      width,
      height,
      layout: { background: { color: "#fff" }, textColor: "#333" },
      rightPriceScale: { borderColor: "#e0e0e0" },
      timeScale: { borderColor: "#e0e0e0" },
    });
    const series = chart.addCandlestickSeries({
      upColor: "#26a69a",
      borderUpColor: "#26a69a",
      wickUpColor: "#26a69a",
      downColor: "#ef5350",
      borderDownColor: "#ef5350",
      wickDownColor: "#ef5350",
      borderVisible: false,
    });
    chartRef.current = chart;
    seriesRef.current = series;

    // Re-render overlay on pan/zoom/resize
    const ts = chart.timeScale();
    const onTimeRange = () => setViewportVersion((v) => v + 1);
    ts.subscribeVisibleTimeRangeChange(onTimeRange);
    // Some setups prefer logical range changes
    try {
      // @ts-ignore - available in recent versions
      ts.subscribeVisibleLogicalRangeChange?.(onTimeRange);
    } catch {}
    const cleanup = () => {
      try {
        ts.unsubscribeVisibleTimeRangeChange(onTimeRange);
        // @ts-ignore
        ts.unsubscribeVisibleLogicalRangeChange?.(onTimeRange);
      } catch {}
      chart.remove();
    };
    return cleanup;
  }, []);

  // Load interval mapping on startup (with graceful fallback)
  useEffect(() => {
    fetchIntervalMapping()
      .then((map) => {
        if (map && Object.keys(map).length > 0) {
          setIntervalMap(map);
          // ensure current selection is valid
          const keys = Object.keys(map) as Timeframe[];
          if (!keys.includes(timeframe)) {
            setTimeframe(keys.includes("1h") ? "1h" : (keys[0] as Timeframe));
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
    seriesRef.current?.setData(
      data.map((b) => ({
        time: b.time as any,
        open: b.open,
        high: b.high,
        low: b.low,
        close: b.close,
      }))
    );
    const ds = await listDrawings(symbol, intervalParam);
    setDraws(ds);
    setViewportVersion((v) => v + 1); // ensure overlay resyncs
  }

  useEffect(() => {
    // auto-load on initial render
    if (chartRef.current) load().catch(console.error);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chartRef.current]);

  const existing = useMemo(() => {
    return draws
      .filter((d) => d.payloadJson)
      .map((d) => ({
        id: d.id as number,
        type: d.type,
        payload: JSON.parse(d.payloadJson),
      }));
  }, [draws]);

  async function handleComplete(type: DrawingType, payload: any) {
    const saved = await createDrawing({
      symbol,
      timeframe, // store uiKey; switch to enumName if you prefer
      type,
      payloadJson: JSON.stringify(payload),
      name: `${type} ${new Date().toISOString()}`,
    } as Drawing);
    setDraws((prev) => [saved, ...prev]);
  }

  async function handleUpdate(id: number, payload: any) {
    const updated = await updateDrawing(id, { payloadJson: JSON.stringify(payload) });
    setDraws((prev) => prev.map((d) => (d.id === id ? updated : d)));
  }

  async function handleDelete(id: number) {
    await deleteDrawing(id);
    setDraws((prev) => prev.filter((d) => d.id !== id));
  }

  const timeframeOptions = useMemo(() => {
    // Attempt a sensible ordering
    const order = ["1m", "3m", "5m", "15m", "30m", "1h", "4h", "1d", "1w"];
    const keys = Object.keys(intervalMap);
    return order.filter((k) => keys.includes(k)).concat(keys.filter((k) => !order.includes(k)));
  }, [intervalMap]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.key === "Delete" || e.key === "Backspace") && selectedId != null) {
        e.preventDefault();
        handleDelete(selectedId).catch(console.error);
        setSelectedId(null);
      }
      // Escape to clear selection
      if (e.key === "Escape" && selectedId != null) {
        setSelectedId(null);
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId]);

  // Helpers to project logical (time/price) <-> screen coords
  function project(pt: { time: number; price: number }): { x: number; y: number } {
    const chart = chartRef.current;
    const series = seriesRef.current;
    if (!chart || !series) return { x: 0, y: 0 };
    const x = chart.timeScale().timeToCoordinate(pt.time as any) ?? 0;
    const y = series.priceToCoordinate(pt.price) ?? 0;
    return { x, y };
  }
  function unproject(p: { x: number; y: number }): { time: number; price: number } {
    const chart = chartRef.current;
    const series = seriesRef.current;
    if (!chart || !series) return { time: 0, price: 0 };
    const t = chart.timeScale().coordinateToTime(p.x) as any;
    let time: number;
    if (typeof t === "number") {
      time = t;
    } else if (t && typeof t === "object" && "year" in t && "month" in t && "day" in t) {
      const bd = t as { year: number; month: number; day: number };
      time = Math.floor(Date.UTC(bd.year, bd.month - 1, bd.day) / 1000);
    } else {
      time = 0;
    }
    const price = series.coordinateToPrice(p.y) ?? 0;
    return { time, price };
  }

  return (
    <div className="container">
      <div className="panel">
        <strong>Symbol</strong>
        <input value={symbol} onChange={(e) => setSymbol(e.target.value)} placeholder="e.g., AAPL" />
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
      </div>

      <div className="chart-wrap">
        <div ref={containerRef} style={{ width, height }} />
        <Legend symbol={symbol} timeframe={timeframe} bars={bars} />
        <ToolDrawer current={tool} onSelect={setTool} />
        <div className="overlay">
          <DrawLayer
            width={width}
            height={height}
            tool={tool}
            existing={existing}
            selectedId={selectedId}
            onSelect={setSelectedId}
            onComplete={handleComplete}
            onUpdate={handleUpdate}
            onDelete={handleDelete}
            project={project}
            unproject={unproject}
            viewportVersion={viewportVersion}
          />
        </div>
      </div>
    </div>
  );
}
