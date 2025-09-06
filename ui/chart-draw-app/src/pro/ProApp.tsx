import React, { useEffect, useRef } from "react";
import { KLineChartPro } from "@klinecharts/pro";
// Import Pro styles so the built-in toolbar and UI render properly
import "@klinecharts/pro/dist/klinecharts-pro.css";
import {KLineData} from "klinecharts";

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
  const chartRef = useRef<any>(null);

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

      class MyDatafeed {
        constructor(
          private defaultSymbol: string,
          private defaultPeriod: string,
          private uiToEnum: Record<string, string>
        ) {}

        // Normalize period from string or object ({ multiplier,timespan,text } or { text/value }) to a UI key like "1h"
        private uiKey(p?: any): string {
          if (!p) return this.defaultPeriod;
          if (typeof p === "string") return p;
          if (typeof p === "object") {
            if (p.text) return String(p.text);
            if (p.value) return String(p.value);
            if (p.multiplier && p.timespan) {
              const mult = Number(p.multiplier) || 1;
              const u = String(p.timespan).toLowerCase();
              const suf = u.startsWith("min") ? "m" : u.startsWith("hour") ? "h" : u.startsWith("day") ? "d" : u.startsWith("week") ? "w" : "";
              if (suf) return `${mult}${suf}`;
            }
          }
          return this.defaultPeriod;
        }

        // Map UI key to server enum name via backend mapping
        private toEnum(p?: any): string {
          const key = this.uiKey(p);
          return this.uiToEnum[key] ?? key;
        }

        // Symbol search (drives the built-in search in Pro)
        searchSymbols(keyword?: string) {
          const url = new URL("/api/symbols", window.location.origin);
          if (keyword) url.searchParams.set("query", keyword);
          return fetch(url)
            .then((r) => (r.ok ? r.json() : []))
            .catch(() => []);
        }

        // Supported periods from backend mapping (only what your server exposes)
        getPeriods() {
          const keys = Object.keys(this.uiToEnum);
          return Promise.resolve(keys);
        }
        // Also expose as properties for implementations that read arrays instead of calling the method
        get periods() {
          return Object.keys(this.uiToEnum);
        }
        get resolutions() {
          return this.periods;
        }
        // Compatibility
        getResolutions() {
          return this.getPeriods();
        }

        // Historical bars (Pro calls this) - pure positional call
        getHistoryKLineData = async (
          symbol: string | undefined,
          period: any,
          from?: number,
          to?: number
        ): Promise<KLineData[]> => {
          const s = (symbol ?? this.defaultSymbol) as string;
          const enumName = this.toEnum(period);
          const url = new URL("/api/ohlc", window.location.origin);
          url.searchParams.set("symbol", s);
          url.searchParams.set("interval", enumName);
          if (from != null) url.searchParams.set("from", String(from));
          if (to != null) url.searchParams.set("to", String(to));
          return fetch(url)
            .then((r) => {
              if (!r.ok) throw new Error(`bars fetch failed ${r.status}`);
              return r.json();
            })
            .then((rows: BarRow[]) =>
              rows.map((b) => ({
                timestamp: (b.time ?? b.timestamp ?? 0) * 1000,
                open: b.open,
                high: b.high,
                low: b.low,
                close: b.close,
                volume: b.volume,
              }))
            );
        };

        // Legacy alias (wraps the above) - pure positional call
        getBars = (
          symbol: string | undefined,
          resolutionOrPeriod: any,
          from?: number,
          to?: number
        ) => {
          // Treat second arg as period (or resolution); mapping is handled in getHistoryKLineData
          return this.getHistoryKLineData(symbol ?? this.defaultSymbol, resolutionOrPeriod ?? this.defaultPeriod, from, to);
        };

        // Realtime (optional)
        getRealtimeKLineData(
          { symbol, period }: { symbol?: string; period?: any },
          onRealtime: (bar: {
            timestamp: number;
            open: number;
            high: number;
            low: number;
            close: number;
            volume: number;
          }) => void
        ) {
          // Implement WS and call onRealtime with same shape; translate interval if needed:
          const _s = (symbol ?? this.defaultSymbol) as string;
          const _enum = this.toEnum(period);
          const handle = { close: () => {} };
          return handle;
        }

        // Expected by Pro: subscribe/unsubscribe for realtime
        subscribe(
          { symbol, period }: { symbol?: string; period?: any },
          onRealtime: (bar: {
            timestamp: number;
            open: number;
            high: number;
            low: number;
            close: number;
            volume: number;
          }) => void
        ) {
          return this.getRealtimeKLineData({ symbol, period }, onRealtime);
        }

        unsubscribe(handle: { close?: () => void } | any) {
          try {
            handle?.close?.();
          } catch {
            // ignore
          }
        }

        // Backward-compat alias delegates to subscribe
        subscribeBars(
          { symbol, resolution, period }: { symbol?: string; resolution?: any; period?: any },
          onRealtime: (bar: any) => void
        ) {
          return this.subscribe(
            { symbol: symbol ?? this.defaultSymbol, period: period ?? resolution ?? this.defaultPeriod },
            onRealtime
          );
        }
      }

      const chart = new KLineChartPro({
        container: containerRef.current!,
        datafeed: new MyDatafeed(DEFAULT_SYMBOL, DEFAULT_PERIOD, mapping),
        symbol: DEFAULT_SYMBOL,
        period: DEFAULT_PERIOD,
        // Provide only backend-supported periods to Pro
        periods: periodItems,
        locale: "en-US",
        layout: {
          toolbar: { visible: true },
          symbolSearch: { visible: true },
          // Inject the exact items so the picker mirrors backend support (fallback to simple text/value)
          resolution: { visible: true, items: periodItems.length ? periodItems : uiKeys.map(k => ({ text: k, value: k })) },
          indicator: { visible: true },
          theme: { toggle: true },
        },
        chart: {
          styles: {
            candle: {
              type: "candle_solid",
              upColor: "#26a69a",
              downColor: "#ef5350",
              upBorderColor: "#26a69a",
              downBorderColor: "#ef5350",
              upWickColor: "#26a69a",
              downWickColor: "#ef5350",
            },
          },
        },
      });

      try {
        chart.setPeriod?.(DEFAULT_PERIOD);
      } catch {
        /* ignore */
      }
      chartRef.current = chart;
    };

    void init();

    return () => {
      try {
        chartRef.current?.destroy?.();
      } catch {
        /* noop */
      }
      chartRef.current = null;
    };
  }, []);

  return <div ref={containerRef} style={{ width: "100%", height: "100vh" }} />;
}
