import React, { useEffect, useRef, useCallback, useState } from "react";
import { createChart, type IChartApi, type CandlestickData } from "lightweight-charts";
import { TrendlinePlugin } from "./plugins/TrendlinePlugin";
import { RayPlugin } from "./plugins/RayPlugin";

type BarRow = {
  time?: number;
  timestamp?: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

export default function ProApp() {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const currentSymbolRef = useRef<string | undefined>(undefined);
  const currentPeriodRef = useRef<string | undefined>(undefined);
  const trendlineRef = useRef<TrendlinePlugin | null>(null);
  const rayRef = useRef<RayPlugin | null>(null);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const [showLineFlyout, setShowLineFlyout] = useState(false);

  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLDivElement>) => {
    const key = e.key.toLowerCase();
    if ((e.altKey || e.metaKey) && key === "t") {
      // default to trendline
      rayRef.current?.cancel?.();
      rayRef.current?.setActive?.(false);
      trendlineRef.current?.setActive?.(true);
      trendlineRef.current?.startDrawing();
      setShowLineFlyout(false);
      e.preventDefault();
      return;
    }
    if (key === "escape") {
      trendlineRef.current?.cancel?.();
      rayRef.current?.cancel?.();
      setShowLineFlyout(false);
      return;
    }
    if (key === "delete" || key === "backspace") {
      const deleted =
        (trendlineRef.current as any)?.deleteSelected?.() ||
        (rayRef.current as any)?.deleteSelected?.();
      if (deleted) {
        e.preventDefault();
      }
    }
  }, []);

  const LOCAL_HISTORY_KEY = "chart_pro_local_history";

  type SavedState = {
    id: string;
    timestamp: number;
    symbol?: string;
    period?: any;
    overlays?: any[];
    indicators?: any[];
  };

  const loadHistory = (): SavedState[] => {
    try {
      const raw = window.localStorage.getItem(LOCAL_HISTORY_KEY);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? (parsed as SavedState[]) : [];
    } catch {
      return [];
    }
  };

  const persistHistory = (items: SavedState[]) => {
    try {
      window.localStorage.setItem(LOCAL_HISTORY_KEY, JSON.stringify(items));
    } catch {
      // ignore write errors (quota, private mode, etc.)
    }
  };

  const collectCurrentState = (): Omit<SavedState, "id" | "timestamp"> => {
    const symbol = currentSymbolRef.current;
    const period = currentPeriodRef.current;
    return { symbol, period, overlays: [], indicators: [] };
  };

  const handleSave = useCallback(() => {
    const now = Date.now();
    const id = `${now}-${Math.random().toString(36).slice(2, 8)}`;
    const base = collectCurrentState();
    const history = loadHistory();
    const next: SavedState[] = [{ id, timestamp: now, ...base }, ...history].slice(0, 50);
    persistHistory(next);
  }, []);

  useEffect(() => {
    if (!containerRef.current) return;

    const init = async () => {
      // Load UI->enum mapping from backend
      let mapping: Record<string, string> = {};
      try {
        const res = await fetch("/api/intervals/mapping");
        if (res.ok) {
          mapping = await res.json();
        }
      } catch {
        mapping = {};
      }

      // Fetch KLine Pro style periods [{ multiplier, timespan, text }]
      let periodItems: Array<{ multiplier: number; timespan: string; text: string }> = [];
      try {
        const res = await fetch("/api/intervals/periods");
        if (res.ok) {
          periodItems = await res.json();
        }
      } catch {
        periodItems = [];
      }

      // Build UI keys from periods (fallback to mapping keys)
      const uiKeys = periodItems.length > 0 ? periodItems.map(p => p.text) : Object.keys(mapping);
      const DEFAULT_SYMBOL = "TCS";
      const DEFAULT_PERIOD = uiKeys.includes("1h") ? "1h" : (uiKeys[0] || "1h");

      currentSymbolRef.current = DEFAULT_SYMBOL;
      currentPeriodRef.current = DEFAULT_PERIOD;

      // Create Lightweight Charts chart
      const container = containerRef.current!;
      const bounds = container.getBoundingClientRect();
      const chart = createChart(container, {
        width: Math.max(200, Math.floor(bounds.width || window.innerWidth)),
        height: Math.max(200, Math.floor(bounds.height || window.innerHeight)),
        layout: {
          background: { color: "#ffffff" },
          textColor: "#333",
        },
        grid: {
          vertLines: { color: "#f0f0f0" },
          horzLines: { color: "#f0f0f0" },
        },
        timeScale: { timeVisible: true, secondsVisible: true },
      });
      chartRef.current = chart;

      const series = chart.addCandlestickSeries({
        upColor: "#26a69a",
        downColor: "#ef5350",
        wickUpColor: "#26a69a",
        wickDownColor: "#ef5350",
        borderVisible: false,
      });

      // Helper to map UI key to server enum
      const toEnum = (p: string) => mapping[p] ?? p;

      // Load historical candles and set to series
      const loadCandles = async (symbol: string, period: string) => {
        const url = new URL("/api/ohlc", window.location.origin);
        url.searchParams.set("symbol", symbol);
        url.searchParams.set("interval", toEnum(period));
        const rows: BarRow[] = await fetch(url)
          .then(r => {
            if (!r.ok) throw new Error(`ohlc fetch failed ${r.status}`);
            return r.json();
          });
        const data: CandlestickData[] = rows.map(b => ({
          time: (b.time ?? b.timestamp ?? 0) as number,
          open: b.open,
          high: b.high,
          low: b.low,
          close: b.close,
        }));
        series.setData(data);
      };

      await loadCandles(DEFAULT_SYMBOL, DEFAULT_PERIOD);

      // Create plugins
      trendlineRef.current = new TrendlinePlugin({ chart, series, container });
      rayRef.current = new RayPlugin({ chart, series, container });

      // Handle resize
      const onResize = () => {
        if (!chartRef.current || !containerRef.current) return;
        const rect = containerRef.current.getBoundingClientRect();
        chartRef.current.applyOptions({
          width: Math.max(200, Math.floor(rect.width)),
          height: Math.max(200, Math.floor(rect.height)),
        });
      };
      window.addEventListener("resize", onResize);
    };

    void init();

    // focus the wrapper so it can receive key events
    try {
      wrapperRef.current?.focus?.();
    } catch {
      /* ignore */
    }

    return () => {
      try {
        trendlineRef.current?.destroy?.();
      } catch {
        /* noop */
      }
      trendlineRef.current = null;
      try {
        rayRef.current?.destroy?.();
      } catch {
        /* noop */
      }
      rayRef.current = null;
      try {
        chartRef.current?.remove?.();
      } catch {
        /* noop */
      }
      chartRef.current = null;
    };
  }, []);

  return (
    <div
      ref={wrapperRef}
      tabIndex={0}
      onKeyDown={handleKeyDown}
      onMouseDown={() => wrapperRef.current?.focus()}
      style={{ position: "relative", width: "100%", height: "100vh", outline: "none" }}
    >
      <div
        ref={containerRef}
        style={{ position: "absolute", top: 0, right: 0, bottom: 0, left: 0 }}
      />

      {/* Left toolbar */}
      <div
        style={{
          position: "absolute",
          top: 12,
          left: 12,
          display: "flex",
          flexDirection: "column",
          gap: 8,
          zIndex: 1000,
        }}
      >
        {/* Line group button with flyout */}
        <div style={{ position: "relative" }}>
          <button
            onClick={() => setShowLineFlyout((v) => !v)}
            title="Line Tools"
            style={{
              width: 40,
              height: 40,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              borderRadius: 8,
              border: "1px solid #e0e0e0",
              background: "#ffffff",
              cursor: "pointer",
              boxShadow: "0 2px 6px rgba(0,0,0,0.08)",
            }}
          >
            <svg width="18" height="18" viewBox="0 0 20 20">
              <path d="M3 17 L17 3" stroke="#333" strokeWidth="2" />
            </svg>
          </button>

          {showLineFlyout && (
            <div
              style={{
                position: "absolute",
                top: 0,
                left: 48,
                display: "flex",
                flexDirection: "column",
                gap: 6,
                padding: 6,
                background: "#fff",
                border: "1px solid #e0e0e0",
                borderRadius: 8,
                boxShadow: "0 8px 20px rgba(0,0,0,0.12)",
              }}
            >
              <button
                onClick={() => {
                  rayRef.current?.cancel?.();
                  rayRef.current?.setActive?.(false);
                  trendlineRef.current?.setActive?.(true);
                  trendlineRef.current?.startDrawing();
                  setShowLineFlyout(false);
                }}
                title="Trend Line (Alt+T)"
                style={{
                  width: 160,
                  height: 36,
                  display: "flex",
                  alignItems: "center",
                  gap: 8,
                  justifyContent: "flex-start",
                  borderRadius: 6,
                  border: "1px solid #eee",
                  background: "#ffffff",
                  cursor: "pointer",
                  padding: "0 10px",
                }}
              >
                <svg width="16" height="16" viewBox="0 0 20 20">
                  <path d="M3 17 L17 3" stroke="#333" strokeWidth="2" />
                </svg>
                <span style={{ fontSize: 12, color: "#333" }}>Trend Line</span>
              </button>

              <button
                onClick={() => {
                  trendlineRef.current?.cancel?.();
                  trendlineRef.current?.setActive?.(false);
                  rayRef.current?.setActive?.(true);
                  rayRef.current?.startDrawing();
                  setShowLineFlyout(false);
                }}
                title="Ray"
                style={{
                  width: 160,
                  height: 36,
                  display: "flex",
                  alignItems: "center",
                  gap: 8,
                  justifyContent: "flex-start",
                  borderRadius: 6,
                  border: "1px solid #eee",
                  background: "#ffffff",
                  cursor: "pointer",
                  padding: "0 10px",
                }}
              >
                <svg width="16" height="16" viewBox="0 0 20 20">
                  <path d="M3 17 L17 3" stroke="#333" strokeWidth="2" />
                  <path d="M17 3 L19 1" stroke="#333" strokeWidth="2" />
                </svg>
                <span style={{ fontSize: 12, color: "#333" }}>Ray</span>
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Save button */}
      <button
        onClick={handleSave}
        style={{
          position: "absolute",
          top: 12,
          right: 12,
          zIndex: 1000,
          padding: "8px 12px",
          borderRadius: 6,
          border: "1px solid #ccc",
          background: "#fff",
          cursor: "pointer",
          boxShadow: "0 2px 6px rgba(0,0,0,0.15)"
        }}
        title="Save current chart setup to local history"
      >
        Save
      </button>
    </div>
  );
}
