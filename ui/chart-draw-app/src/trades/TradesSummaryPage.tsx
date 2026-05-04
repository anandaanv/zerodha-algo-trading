import React, { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { fetchTrades, fetchScreenerTypes, fetchPatterns } from "./tradesApi";
import type { Trade } from "./types";
import { TradesTable } from "./TradesTable";

const PATTERN_FILTER_KEY = "trades.filter.pattern";

function toInputDate(d: Date) {
  // yyyy-MM-dd
  const pad = (n: number) => n.toString().padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function fromInputDate(s?: string | null) {
  if (!s) return undefined;
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return undefined;
  return d;
}

export const TradesSummaryPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();

  // Defaults - show only today's trades
  const today = new Date();
  const defaultFrom = today;
  const defaultTo = today;

  const [from, setFrom] = useState<string>(searchParams.get("from") || toInputDate(defaultFrom));
  const [to, setTo] = useState<string>(searchParams.get("to") || toInputDate(defaultTo));
  const [openOnly, setOpenOnly] = useState<boolean>(
    (searchParams.get("open") ?? "true").toLowerCase() !== "false"
  );
  const [script, setScript] = useState<string>(searchParams.get("script") || "");
  const [side, setSide] = useState<string>(searchParams.get("side") || "ALL");
  const [timeframe, setTimeframe] = useState<string>(searchParams.get("timeframe") || "");
  const [screenerType, setScreenerType] = useState<string>(searchParams.get("screenerType") || "");
  const [pattern, setPattern] = useState<string>(() => {
    const fromUrl = searchParams.get("pattern");
    if (fromUrl) return fromUrl;
    return localStorage.getItem(PATTERN_FILTER_KEY) || "";
  });

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [trades, setTrades] = useState<Trade[]>([]);
  const [screenerTypeOptions, setScreenerTypeOptions] = useState<string[]>([]);
  const [patternOptions, setPatternOptions] = useState<string[]>([]);

  const queryFrom = useMemo(() => fromInputDate(from), [from]);
  const queryTo = useMemo(() => {
    // Include full "to" day end
    const d = fromInputDate(to);
    if (!d) return undefined;
    return new Date(d.getTime() + 24 * 60 * 60 * 1000 - 1);
  }, [to]);

  // Persist pattern filter to localStorage
  useEffect(() => {
    if (pattern) localStorage.setItem(PATTERN_FILTER_KEY, pattern);
    else localStorage.removeItem(PATTERN_FILTER_KEY);
  }, [pattern]);

  // Fetch dropdown options on mount
  useEffect(() => {
    fetchScreenerTypes().then(setScreenerTypeOptions).catch(() => {});
    fetchPatterns().then(setPatternOptions).catch(() => {});
  }, []);

  useEffect(() => {
    const params: Record<string, string> = {};
    if (from) params.from = from;
    if (to) params.to = to;
    params.open = String(openOnly);
    if (script) params.script = script;
    if (timeframe) params.timeframe = timeframe;
    if (side && side !== "ALL") params.side = side;
    if (screenerType) params.screenerType = screenerType;
    if (pattern) params.pattern = pattern;
    setSearchParams(params, { replace: true });
  }, [from, to, openOnly, script, side, timeframe, screenerType, pattern, setSearchParams]);

  useEffect(() => {
    let aborted = false;
    async function load() {
      try {
        setLoading(true);
        setError(null);
        const data = await fetchTrades({
          from: queryFrom,
          to: queryTo,
          open: openOnly,
          status: openOnly ? "ACTIVE" : undefined,
          script: script || undefined,
          timeframe: timeframe || undefined,
          side: side && side !== "ALL" ? side : undefined,
          screenerType: screenerType || undefined,
          pattern: pattern || undefined,
        });
        if (!aborted) setTrades(data);
      } catch (e: any) {
        if (!aborted) setError(e?.message || "Unknown error");
      } finally {
        if (!aborted) setLoading(false);
      }
    }
    load();
    return () => {
      aborted = true;
    };
  }, [queryFrom, queryTo, openOnly, script, side, timeframe, screenerType, pattern]);

  return (
    <div style={{ padding: 16 }}>
      <h1 style={{ marginBottom: 12 }}>Trades Summary</h1>

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
          gap: 12,
          marginBottom: 16,
          alignItems: "end",
        }}
      >
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>From</label>
          <input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          />
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>To</label>
          <input
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          />
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>Script</label>
          <input
            type="text"
            placeholder="e.g. AAPL"
            value={script}
            onChange={(e) => setScript(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          />
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>Timeframe</label>
          <input
            type="text"
            placeholder="e.g. H1"
            value={timeframe}
            onChange={(e) => setTimeframe(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          />
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>Side</label>
          <select
            value={side}
            onChange={(e) => setSide(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          >
            <option value="ALL">All</option>
            <option value="BUY">BUY</option>
            <option value="SELL">SELL</option>
          </select>
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>Screener</label>
          <select
            value={screenerType}
            onChange={(e) => setScreenerType(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          >
            <option value="">All</option>
            {screenerTypeOptions.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>
            Pattern{" "}
            {pattern && (
              <span style={{ fontSize: 11, color: "#666", marginLeft: 4 }}>(saved)</span>
            )}
          </label>
          <select
            value={pattern}
            onChange={(e) => setPattern(e.target.value)}
            style={{ width: "100%", padding: 8 }}
          >
            <option value="">All</option>
            {patternOptions.map((p) => (
              <option key={p} value={p}>{p}</option>
            ))}
          </select>
        </div>
        <div>
          <label style={{ display: "block", marginBottom: 4 }}>Active only</label>
          <input
            type="checkbox"
            checked={openOnly}
            onChange={(e) => setOpenOnly(e.target.checked)}
          />{" "}
          Open
        </div>
      </div>

      <TradesTable trades={trades} loading={loading} error={error} />
    </div>
  );
};
