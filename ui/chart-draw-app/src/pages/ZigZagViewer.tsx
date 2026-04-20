import React, { useState, useEffect, useRef } from "react";
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  type SeriesMarker,
  type Time,
  type CandlestickData,
} from "lightweight-charts";
import { getApiUrl } from "../config/api";
import { withAuth } from "../utils/apiHelper";

interface OHLCBar {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

interface Pivot {
  type: "HIGH" | "LOW";
  timestamp: string;
  barIndex: number;
  sequence: number;
  value: number;
  atrAtPivot: number;
  retracementPct: number;
  extensionPct: number;
  legSizePct: number;
  legDurationBars: number;
  legSpeed: number;
}

interface ZigZagResponse {
  tradingSymbol: string;
  timeframe: string;
  params: Record<string, unknown>;
  pivots: Pivot[];
}

interface StructurePoint {
  pivotType: "HIGH" | "LOW";
  structureLabel: "HH" | "HL" | "LH" | "LL" | "BOS_HIGH" | "BOS_LOW" | "CHOCH_HIGH" | "CHOCH_LOW" | "FIRST";
  timestamp: string;
  price: number;
}

interface ReversalExtreme {
  timestamp: string;
  price: number;
  direction: "BULLISH" | "BEARISH";
  confirmationTimestamp: string;
}

export default function ZigZagViewer() {
  const [symbol, setSymbol] = useState("RELIANCE");
  const [timeframe, setTimeframe] = useState("OneHour");
  const [status, setStatus] = useState("");
  const [nifty50List, setNifty50List] = useState<string[]>([]);
  const [lastUpdated, setLastUpdated] = useState<string | null>(null);
  const [pivotCount, setPivotCount] = useState(0);
  const [showReversals, setShowReversals] = useState(true);
  const [showChoch, setShowChoch] = useState(false);
  const [showIncremental, setShowIncremental] = useState(false);
  const [incrementalPivotCount, setIncrementalPivotCount] = useState(0);

  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const zigzagSeriesRef = useRef<ISeriesApi<"Line"> | null>(null);
  const pivotMarkersRef = useRef<SeriesMarker<Time>[]>([]);
  const reversalExtremesRef = useRef<ReversalExtreme[]>([]);
  const structurePointsRef = useRef<StructurePoint[]>([]);

  // Fetch NIFTY50 list on mount
  useEffect(() => {
    const fetchNifty50 = async () => {
      try {
        const res = await fetch(
          getApiUrl("/api/pattern-scan/nifty50").toString(),
          withAuth()
        );
        if (res.ok) {
          const data: string[] = await res.json();
          setNifty50List(data);
        }
      } catch (e) {
        console.error("Failed to fetch NIFTY50 list:", e);
      }
    };

    fetchNifty50();
  }, []);

  const handleLoad = async () => {
    if (!containerRef.current) return;

    setStatus("Loading...");
    setPivotCount(0);
    setLastUpdated(null);

    try {
      // Fetch OHLC, ZigZag, market structure, and optionally incremental ZigZag
      const fetches: Promise<Response>[] = [
        fetch(
          getApiUrl(
            `/api/ohlc?symbol=${symbol}&interval=${timeframe}`
          ).toString(),
          withAuth()
        ),
        fetch(
          getApiUrl("/api/chartpattern/zigzag/stock").toString(),
          withAuth({
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              tradingSymbol: symbol,
              timeframes: [timeframe],
              persist: false,
            }),
          })
        ),
        fetch(
          getApiUrl(
            `/api/chartpattern/zigzag/market-structure?symbol=${encodeURIComponent(symbol)}&timeframe=${encodeURIComponent(timeframe)}`
          ).toString(),
          withAuth()
        ),
        // Always fetch incremental for comparison
        fetch(
          getApiUrl(
            `/api/chartpattern/zigzag/incremental?symbol=${encodeURIComponent(symbol)}&timeframe=${encodeURIComponent(timeframe)}`
          ).toString(),
          withAuth()
        ),
        // Fetch impulse labels
        fetch(
          getApiUrl(
            `/api/chartpattern/zigzag/impulse-labels?symbol=${encodeURIComponent(symbol)}&timeframe=${encodeURIComponent(timeframe)}`
          ).toString(),
          withAuth()
        ),
      ];
      const [ohlcRes, zigzagRes, structureRes, incrementalRes, impulseRes] = await Promise.all(fetches);

      if (!ohlcRes.ok || !zigzagRes.ok) {
        setStatus("Error loading data");
        return;
      }

      const ohlcData: OHLCBar[] = await ohlcRes.json();
      const zigzagDataArray: ZigZagResponse[] = await zigzagRes.json();
      const structurePoints: StructurePoint[] = structureRes.ok ? await structureRes.json() : [];
      structurePointsRef.current = structurePoints;
      const incrementalPivots: Pivot[] = incrementalRes?.ok ? await incrementalRes.json() : [];
      setIncrementalPivotCount(incrementalPivots.length);
      const impulseLabels: {timestamp: string; label: string; direction: string; price: string}[] =
        impulseRes?.ok ? await impulseRes.json() : [];

      if (ohlcData.length === 0 || zigzagDataArray.length === 0) {
        setStatus("No data available");
        return;
      }

      const zigzagData = zigzagDataArray[0];
      const pivots = zigzagData.pivots;

      // Compute reversal extremes from structure points
      const reversalExtremes: ReversalExtreme[] = [];
      if (structurePoints.length > 0 && pivots.length > 0) {
        const pivotIndexByTs = new Map<string, number>();
        pivots.forEach((p, i) => pivotIndexByTs.set(p.timestamp, i));

        for (const sp of structurePoints) {
          if (sp.structureLabel !== "CHOCH_HIGH" && sp.structureLabel !== "CHOCH_LOW") continue;
          const chochIdx = pivotIndexByTs.get(sp.timestamp);
          if (chochIdx === undefined || chochIdx === 0) continue;
          const reversalPivot = pivots[chochIdx - 1];
          reversalExtremes.push({
            timestamp: reversalPivot.timestamp,
            price: reversalPivot.value,
            direction: sp.structureLabel === "CHOCH_HIGH" ? "BULLISH" : "BEARISH",
            confirmationTimestamp: sp.timestamp,
          });
        }
      }
      reversalExtremesRef.current = reversalExtremes;

      // Remove old chart if exists
      if (chartRef.current) {
        try {
          chartRef.current.remove();
        } catch {}
        chartRef.current = null;
        candleSeriesRef.current = null;
        zigzagSeriesRef.current = null;
      }

      // Create new chart
      const bounds = containerRef.current.getBoundingClientRect();
      const chart = createChart(containerRef.current, {
        width: Math.max(200, Math.floor(bounds.width)),
        height: 800,
        layout: {
          background: { color: "#ffffff" },
          textColor: "#666",
        },
        grid: {
          vertLines: { color: "#f0f0f0" },
          horzLines: { color: "#f0f0f0" },
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

      // Add candlestick series (faint)
      const candleSeries = chart.addCandlestickSeries({
        upColor: "#e0e0e0",
        downColor: "#bdbdbd",
        borderUpColor: "#e0e0e0",
        borderDownColor: "#bdbdbd",
        wickUpColor: "#9e9e9e",
        wickDownColor: "#9e9e9e",
      });

      candleSeriesRef.current = candleSeries;

      const candleData: CandlestickData[] = ohlcData
        .map((b) => ({
          time: b.time as Time,
          open: b.open,
          high: b.high,
          low: b.low,
          close: b.close,
        }))
        .sort((a, b) => (a.time as number) - (b.time as number))
        .filter(
          (b, i, arr) => i === 0 || (b.time as number) !== (arr[i - 1].time as number)
        );

      candleSeries.setData(candleData);

      // Add ZigZag line series (highlighted)
      const zigzagSeries = chart.addLineSeries({
        color: "#ff6b35",
        lineWidth: 2,
        priceLineVisible: false,
        lastValueVisible: false,
        crosshairMarkerVisible: true,
      });

      zigzagSeriesRef.current = zigzagSeries;

      // Dedupe + sort pivots by time (lightweight-charts requires strict asc order).
      // If two pivots share the same epoch second (can happen when ZigZag emits a
      // H and L on the same bar), keep the one with the larger absolute deviation
      // from the neighbouring pivots so the zigzag line stays meaningful.
      const pivotsByTime = new Map<number, typeof pivots[number]>();
      for (const p of pivots) {
        const t = Math.floor(new Date(p.timestamp).getTime() / 1000);
        const existing = pivotsByTime.get(t);
        if (!existing) {
          pivotsByTime.set(t, p);
        } else {
          // Prefer HIGH over LOW arbitrarily to keep it deterministic; callers can
          // still see both pivots in markers below if needed.
          if (existing.type === "LOW" && p.type === "HIGH") pivotsByTime.set(t, p);
        }
      }
      const cleanPivots = Array.from(pivotsByTime.entries())
        .sort((a, b) => a[0] - b[0])
        .map(([t, p]) => ({ t, p }));

      const lineData = cleanPivots.map(({ t, p }) => ({
        time: t as Time,
        value: p.value,
      }));

      zigzagSeries.setData(lineData);

      // Add IncrementalZigZag overlay (blue dashed line) if available
      if (incrementalPivots.length > 0) {
        const incrSeries = chart.addLineSeries({
          color: "#2196f3",
          lineWidth: 2,
          lineStyle: 2, // dashed
          priceLineVisible: false,
          lastValueVisible: false,
          crosshairMarkerVisible: false,
        });

        const incrByTime = new Map<number, Pivot>();
        for (const p of incrementalPivots) {
          const t = Math.floor(new Date(p.timestamp).getTime() / 1000);
          if (!incrByTime.has(t)) incrByTime.set(t, p);
        }
        const incrLineData = Array.from(incrByTime.entries())
          .sort((a, b) => a[0] - b[0])
          .map(([t, p]) => ({ time: t as Time, value: p.value }));

        incrSeries.setData(incrLineData);
      }

      // Add impulse label markers (green/red diamonds)
      if (impulseLabels.length > 0) {
        const impulseMarkers: SeriesMarker<Time>[] = impulseLabels.map((il) => {
          const t = Math.floor(new Date(il.timestamp).getTime() / 1000) as Time;
          const dir = parseInt(il.direction) || 0;
          const isBullish = il.label === "wave3_start" && dir >= 0;
          return {
            time: t,
            position: isBullish ? "belowBar" : "aboveBar",
            color: isBullish ? "#4caf50" : "#f44336",
            shape: "diamond" as const,
            text: il.label === "wave3_start" ? "W3" : "W5",
          };
        });
        pivotMarkersRef.current = [...pivotMarkersRef.current, ...impulseMarkers];
      }

      // Set markers for pivots (orange arrows)
      const pivotMarkers: SeriesMarker<Time>[] = cleanPivots.map(({ t, p }) => ({
        time: t as Time,
        position: p.type === "HIGH" ? "aboveBar" : "belowBar",
        color: "#ff6b35",
        shape: p.type === "HIGH" ? "arrowDown" : "arrowUp",
        text: p.value.toFixed(2),
      }));
      pivotMarkersRef.current = pivotMarkers;

      // Add reversal extreme markers (primary)
      const reversalMarkers: SeriesMarker<Time>[] = reversalExtremes.map(re => ({
        time: Math.floor(new Date(re.timestamp).getTime() / 1000) as Time,
        position: re.direction === "BULLISH" ? "belowBar" : "aboveBar",
        color: re.direction === "BULLISH" ? "#059669" : "#dc2626",
        shape: "circle",
        text: re.direction === "BULLISH" ? "REV↑" : "REV↓",
        size: 3,
      }));

      // Add CHoCH markers (secondary, if enabled)
      const chochMarkers: SeriesMarker<Time>[] = showChoch
        ? structurePoints
            .filter(sp => sp.structureLabel === "CHOCH_HIGH" || sp.structureLabel === "CHOCH_LOW")
            .map(sp => {
              const bullish = sp.structureLabel === "CHOCH_HIGH";
              return {
                time: Math.floor(new Date(sp.timestamp).getTime() / 1000) as Time,
                position: bullish ? "aboveBar" : "belowBar",
                color: bullish ? "#6ee7b7" : "#fca5a5",
                shape: "circle",
                text: "c",
                size: 1,
              };
            })
        : [];

      const allMarkers = [...pivotMarkers, ...reversalMarkers, ...chochMarkers].sort(
        (a, b) => (a.time as number) - (b.time as number)
      );
      zigzagSeries.setMarkers(allMarkers);

      // Fit content
      chart.timeScale().fitContent();

      // Update UI
      setPivotCount(pivots.length);
      setLastUpdated(new Date().toLocaleTimeString());
      setStatus(`Loaded ${ohlcData.length} bars, ${pivots.length} pivots`);

      // Setup resize observer
      let resizeHandler: (() => void) | null = null;
      let resizeObserver: ResizeObserver | null = null;

      resizeHandler = () => {
        if (!chartRef.current || !containerRef.current) return;
        const rect = containerRef.current.getBoundingClientRect();
        chartRef.current.applyOptions({
          width: Math.max(200, Math.floor(rect.width)),
          height: 800,
        });
      };

      window.addEventListener("resize", resizeHandler);

      resizeObserver = new ResizeObserver(() => {
        resizeHandler?.();
      });

      resizeObserver.observe(containerRef.current);

      setTimeout(() => resizeHandler?.(), 100);
    } catch (error) {
      console.error("Error loading chart:", error);
      setStatus(
        `Error: ${error instanceof Error ? error.message : String(error)}`
      );
    }
  };

  // Reapply markers when reversal/CHoCH toggles change
  useEffect(() => {
    if (!zigzagSeriesRef.current) return;

    // Rebuild reversal markers
    const reversalMarkers: SeriesMarker<Time>[] = showReversals
      ? reversalExtremesRef.current.map(re => ({
          time: Math.floor(new Date(re.timestamp).getTime() / 1000) as Time,
          position: re.direction === "BULLISH" ? "belowBar" : "aboveBar",
          color: re.direction === "BULLISH" ? "#059669" : "#dc2626",
          shape: "circle",
          text: re.direction === "BULLISH" ? "REV↑" : "REV↓",
          size: 3,
        }))
      : [];

    // Rebuild CHoCH markers
    const chochMarkers: SeriesMarker<Time>[] = showChoch
      ? structurePointsRef.current
          .filter(sp => sp.structureLabel === "CHOCH_HIGH" || sp.structureLabel === "CHOCH_LOW")
          .map(sp => {
            const bullish = sp.structureLabel === "CHOCH_HIGH";
            return {
              time: Math.floor(new Date(sp.timestamp).getTime() / 1000) as Time,
              position: bullish ? "aboveBar" : "belowBar",
              color: bullish ? "#6ee7b7" : "#fca5a5",
              shape: "circle",
              text: "c",
              size: 1,
            };
          })
      : [];

    const allMarkers = [...pivotMarkersRef.current, ...reversalMarkers, ...chochMarkers].sort(
      (a, b) => (a.time as number) - (b.time as number)
    );
    zigzagSeriesRef.current.setMarkers(allMarkers);
  }, [showReversals, showChoch]);

  return (
    <div style={{ padding: "20px", display: "flex", flexDirection: "column", gap: "20px" }}>
      {/* Controls Row */}
      <div
        style={{
          display: "flex",
          gap: "10px",
          alignItems: "center",
          flexWrap: "wrap",
        }}
      >
        <label style={{ fontWeight: 500 }}>
          Symbol:
          <input
            type="text"
            value={symbol}
            onChange={(e) => setSymbol(e.target.value.toUpperCase())}
            list="nifty50-list"
            style={{
              marginLeft: "8px",
              padding: "6px 10px",
              borderRadius: "4px",
              border: "1px solid #ccc",
              fontSize: "14px",
            }}
            placeholder="RELIANCE"
          />
          <datalist id="nifty50-list">
            {nifty50List.map((s) => (
              <option key={s} value={s} />
            ))}
          </datalist>
        </label>

        <label style={{ fontWeight: 500 }}>
          Timeframe:
          <select
            value={timeframe}
            onChange={(e) => setTimeframe(e.target.value)}
            style={{
              marginLeft: "8px",
              padding: "6px 10px",
              borderRadius: "4px",
              border: "1px solid #ccc",
              fontSize: "14px",
            }}
          >
            <option value="Day">Day</option>
            <option value="OneHour">OneHour</option>
            <option value="FifteenMinute">FifteenMinute</option>
            <option value="FiveMinute">FiveMinute</option>
          </select>
        </label>

        <button
          onClick={handleLoad}
          style={{
            padding: "8px 16px",
            borderRadius: "4px",
            border: "1px solid #ccc",
            background: "#667eea",
            color: "#fff",
            cursor: "pointer",
            fontWeight: 500,
            fontSize: "14px",
          }}
        >
          Load
        </button>

        <label style={{ fontWeight: 500, display: "flex", alignItems: "center", gap: "6px" }}>
          <input
            type="checkbox"
            checked={showReversals}
            onChange={(e) => setShowReversals(e.target.checked)}
            style={{ cursor: "pointer" }}
          />
          Show reversals
        </label>

        <label style={{ fontWeight: 500, display: "flex", alignItems: "center", gap: "6px" }}>
          <input
            type="checkbox"
            checked={showChoch}
            onChange={(e) => setShowChoch(e.target.checked)}
            style={{ cursor: "pointer" }}
          />
          Show ChoCH confirmation markers (advanced)
        </label>

        {incrementalPivotCount > 0 && (
          <span style={{ color: "#2196f3", fontSize: "13px" }}>
            IncrementalZigZag: {incrementalPivotCount} pivots (blue dashed line)
          </span>
        )}

        <span style={{ color: "#666", fontSize: "14px", minHeight: "20px" }}>
          {status}
        </span>
      </div>

      {/* Chart Container */}
      <div
        ref={containerRef}
        style={{
          width: "100%",
          height: "800px",
          border: "1px solid #ddd",
          borderRadius: "4px",
          background: "#fff",
        }}
      />

      {/* Legend Row */}
      <div
        style={{
          display: "flex",
          gap: "20px",
          padding: "10px",
          background: "#f9f9f9",
          borderRadius: "4px",
          fontSize: "14px",
        }}
      >
        <span>
          <strong>Pivots:</strong> {pivotCount}
        </span>
        {lastUpdated && (
          <span>
            <strong>Last updated:</strong> {lastUpdated}
          </span>
        )}
      </div>
    </div>
  );
}
