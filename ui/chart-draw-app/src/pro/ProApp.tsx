import React, { useEffect, useRef, useCallback, useState, useMemo } from "react";
import { createChart, type IChartApi, type CandlestickData } from "lightweight-charts";
import { getPluginsByGroup, getAllPlugins } from "./plugins/PluginRegistry";
// Ensure plugin modules are imported so they self-register
import "./plugins/generic/lines/MultiPointLinePlugin";
import "./plugins/generic/lines/TrendLinePlugin";
import "./plugins/generic/lines/HLinePlugin";
import "./plugins/generic/fib/FibPlugin";
import "./plugins/generic/fib/FibExtPlugin";
import "./plugins/generic/elliott/ImpulseWavePlugin";
import "./plugins/generic/elliott/CorrectiveABCPlugin";
import SimplePropertiesDialog, { type SimpleStyle } from "./SimplePropertiesDialog";

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
  const wrapperRef = useRef<HTMLDivElement>(null);
  const [openGroup, setOpenGroup] = useState<string | null>(null);

  // map key -> instance
  const pluginMapRef = useRef<Record<string, any>>({});
  const groups = useMemo(() => getPluginsByGroup(), []);
  const allDefs = useMemo(() => getAllPlugins(), []);

  const [showProps, setShowProps] = useState(false);
  const [propsInitial, setPropsInitial] = useState<SimpleStyle>({ color: "#1976d2", width: 2, style: "solid" });

  // UI state for Save button visual feedback
  const [saveHover, setSaveHover] = useState(false);
  const [saveActive, setSaveActive] = useState(false);

  const openPropsDialog = useCallback(() => {
    const pm = pluginMapRef.current;
    // Prefer the plugin that currently has a selection
    let style: SimpleStyle | null = null;
    for (const k of Object.keys(pm)) {
      if (pm[k]?.hasSelection?.()) {
        style = pm[k]?.getSelectedStyle?.() ?? null;
        break;
      }
    }
    // Fallback to the first plugin that returns a style, or default
    if (!style) {
      for (const k of Object.keys(pm)) {
        style = pm[k]?.getSelectedStyle?.() ?? null;
        if (style) break;
      }
    }
    setPropsInitial(style ?? { color: "#1976d2", width: 2, style: "solid" });
    setShowProps(true);
  }, []);

  const applyPropsDialog = useCallback((s: SimpleStyle) => {
    const pm = pluginMapRef.current;
    let applied = false;
    // Apply to whichever plugin has a selection
    for (const k of Object.keys(pm)) {
      if (pm[k]?.hasSelection?.()) {
        applied = pm[k]?.applySelectedStyle?.(s) || applied;
      }
    }
    // If none selected, attempt all (no-op where not selected)
    if (!applied) {
      for (const k of Object.keys(pm)) pm[k]?.applySelectedStyle?.(s);
    }
    setShowProps(false);
  }, []);

  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLDivElement>) => {
    const key = e.key.toLowerCase();
    if ((e.altKey || e.metaKey) && key === "t") {
      // default to trendline via registry
      const pm = pluginMapRef.current;
      pm["ray"]?.cancel?.();
      pm["ray"]?.setActive?.(false);
      pm["trendline"]?.setActive?.(true);
      pm["trendline"]?.startDrawing?.();
      setOpenGroup(null);
      e.preventDefault();
      return;
    }
    if (key === "escape") {
      const pm = pluginMapRef.current;
      for (const k of Object.keys(pm)) pm[k]?.cancel?.();
      setOpenGroup(null);
      return;
    }
    if (key === "delete" || key === "backspace") {
      const pm = pluginMapRef.current;
      let deleted = false;
      for (const k of Object.keys(pm)) {
        deleted = pm[k]?.deleteSelected?.() || deleted;
      }
      if (deleted) e.preventDefault();
    }
  }, []);

  const LOCAL_HISTORY_KEY = "chart_pro_local_history";
  const LOCAL_OVERLAYS_KEY = "lwc_overlays_v1";

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

  function saveOverlaysToLocal(symbol: string, period: string) {
    try {
      const raw = window.localStorage.getItem(LOCAL_OVERLAYS_KEY);
      const obj = raw ? JSON.parse(raw) : {};
      obj[symbol] = obj[symbol] || {};
      obj[symbol][period] = obj[symbol][period] || {};
      const instances = pluginMapRef.current;
      for (const def of getAllPlugins()) {
        const data = (instances[def.key] as any)?.exportAll?.() ?? [];
        obj[symbol][period][def.key] = data;
      }
      window.localStorage.setItem(LOCAL_OVERLAYS_KEY, JSON.stringify(obj));
    } catch (e) {
      console.warn("Save overlays failed", e);
    }
  }

  function loadOverlaysFromLocal(symbol: string, period: string) {
    try {
      const raw = window.localStorage.getItem(LOCAL_OVERLAYS_KEY);
      if (!raw) return;
      const obj = JSON.parse(raw);
      const entry = obj?.[symbol]?.[period];
      if (!entry) return;
      const instances = pluginMapRef.current;
      for (const def of getAllPlugins()) {
        const data = entry[def.key] ?? [];
        (instances[def.key] as any)?.importAll?.(data);
      }
      // Backward compatibility for earlier keys
      if (entry.trendlines && instances["trendline"]) (instances["trendline"] as any)?.importAll?.(entry.trendlines);
      if (entry.rays && instances["ray"]) (instances["ray"] as any)?.importAll?.(entry.rays);
    } catch (e) {
      console.warn("Load overlays failed", e);
    }
  }

  const handleSave = useCallback(() => {
    const now = Date.now();
    const id = `${now}-${Math.random().toString(36).slice(2, 8)}`;
    const base = collectCurrentState();
    const history = loadHistory();
    const next: SavedState[] = [{ id, timestamp: now, ...base }, ...history].slice(0, 50);
    persistHistory(next);
    // Save overlays keyed by symbol and period
    if (base.symbol && base.period) {
      saveOverlaysToLocal(base.symbol, String(base.period));
    }
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

      // Create plugins from registry
      const instances: Record<string, any> = {};
      for (const def of getAllPlugins()) {
        try {
          instances[def.key] = new (def.ctor as any)({ chart, series, container });
        } catch (err) {
          console.error("Failed to init plugin", def.key, err);
        }
      }
      pluginMapRef.current = instances;

      // Load saved overlays after chart/plugins are ready
      setTimeout(() => {
        loadOverlaysFromLocal(DEFAULT_SYMBOL, DEFAULT_PERIOD);
      }, 0);

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
        const instances = pluginMapRef.current;
        for (const key of Object.keys(instances)) {
          instances[key]?.destroy?.();
        }
      } catch {
        /* noop */
      }
      try {
        chartRef.current?.remove?.();
      } catch {
        /* noop */
      }
      chartRef.current = null;
      pluginMapRef.current = {};
    };
  }, []);

  const handleWrapperMouseDown = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    wrapperRef.current?.focus();
    const x = e.clientX;
    const y = e.clientY;
    const instances = pluginMapRef.current;
    let selected = false;
    // try in reverse registration order to mimic topmost selection
    const keys = Object.keys(instances);
    for (let i = keys.length - 1; i >= 0; i--) {
      if ((instances[keys[i]] as any)?.trySelectAt?.(x, y)) {
        selected = true;
        break;
      }
    }
    if (!selected) {
      for (const k of keys) (instances[k] as any)?.clearSelection?.();
    }
  }, []);

  return (
    <div
      ref={wrapperRef}
      tabIndex={0}
      onKeyDown={handleKeyDown}
      onMouseDown={handleWrapperMouseDown}
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
        {Object.entries(groups).map(([group, defs]) => (
          <div key={group} style={{ position: "relative" }}>
            <button
              onClick={() => setOpenGroup((g) => (g === group ? null : group))}
              title={`${group} Tools`}
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
              {/* Use first plugin icon in group as group icon */}
              {defs[0]?.icon?.()}
            </button>

            {openGroup === group && (
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
                {defs.map((def) => (
                  <button
                    key={def.key}
                    onClick={() => {
                      // cancel other drawings and make chosen active
                      for (const other of allDefs) {
                        if (other.key !== def.key) {
                          (pluginMapRef.current[other.key] as any)?.cancel?.();
                          (pluginMapRef.current[other.key] as any)?.setActive?.(false);
                        }
                      }
                      (pluginMapRef.current[def.key] as any)?.setActive?.(true);
                      (pluginMapRef.current[def.key] as any)?.startDrawing?.();
                      setOpenGroup(null);
                    }}
                    title={def.title}
                    style={{
                      width: 180,
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
                    {def.icon?.()}
                    <span style={{ fontSize: 12, color: "#333" }}>{def.title}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}

        {/* Properties button (gear) */}
        <button
          onClick={openPropsDialog}
          title="Properties"
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
            <path d="M8 2 L12 2 L13 5 L16 6 L16 10 L13 11 L12 14 L8 14 L7 11 L4 10 L4 6 L7 5 Z" stroke="#333" fill="none" />
            <circle cx="10" cy="8" r="2" fill="#333" />
          </svg>
        </button>
      </div>

      {/* Save button */}
      <button
        onClick={handleSave}
        onMouseEnter={() => setSaveHover(true)}
        onMouseLeave={() => {
          setSaveHover(false);
          setSaveActive(false);
        }}
        onMouseDown={() => setSaveActive(true)}
        onMouseUp={() => setSaveActive(false)}
        style={{
          position: "absolute",
          top: 12,
          right: 12,
          zIndex: 1000,
          display: "inline-flex",
          alignItems: "center",
          gap: 8,
          padding: "10px 14px",
          borderRadius: 10,
          border: "1px solid transparent",
          background: saveHover
            ? "linear-gradient(135deg, #1e88e5, #42a5f5)"
            : "linear-gradient(135deg, #1976d2, #2196f3)",
          color: "#fff",
          cursor: "pointer",
          boxShadow: saveActive
            ? "inset 0 2px 6px rgba(0,0,0,0.28)"
            : saveHover
            ? "0 8px 20px rgba(25,118,210,0.28)"
            : "0 4px 12px rgba(25,118,210,0.2)",
          transform: saveActive ? "translateY(1px) scale(0.99)" : "translateY(0) scale(1)",
          transition: "box-shadow 140ms ease, transform 90ms ease, background 220ms ease",
          userSelect: "none",
        }}
        title="Save current chart setup to local history"
        aria-label="Save current chart setup to local history"
      >
        <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <path d="M4 4 L16 4 L16 16 L4 16 Z" stroke="#fff" strokeWidth="2" fill="none" />
          <path d="M6 8 L14 8" stroke="#fff" strokeWidth="2" />
          <path d="M6 12 L12 12" stroke="#fff" strokeWidth="2" />
        </svg>
        Save
      </button>

      {/* Properties dialog */}
      <SimplePropertiesDialog
        open={showProps}
        initial={propsInitial}
        onApply={applyPropsDialog}
        onClose={() => setShowProps(false)}
      />
    </div>
  );
}
